package app.omnisonic.core.data.sync

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import app.omnisonic.core.data.db.entity.AlbumArtistEntity
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.InternetRadioEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongArtistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.core.data.json.JsonText
import app.omnisonic.core.model.runCatchingCancellable
import app.omnisonic.core.model.text.TextNormalizer
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.AllowAllRunGate
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderAuthException
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits

/**
 * Server-to-device library walk for Subsonic-compatible providers. Writes are
 * column-scoped: server-owned fields are updated in place and locally-owned
 * playback state (play counts, favorites, ratings, resume points, replay gain,
 * clips, editorial text) is never in the SET list. Each album (and each entity
 * batch) commits in its own short transaction so concurrent writers never wait
 * for the whole walk.
 *
 * Every run mints a unique id (`lastSeenSyncId`, D-10) and stamps each row it
 * writes; full-sync pruning compares stamps instead of passing the seen-id
 * lists through SQL (Android 10's SQLite caps statements at 999 variables).
 * After a FULL sync only, rows the server no longer reports are pruned:
 * untouched songs are deleted, songs with play history, pending outbox rows or
 * D-9 local state (rating, resume point, clip) are tombstoned with
 * missingOnServer=true, albums die with their last song, artists die with
 * their last link, and vanished playlists are removed outright. Pruning only
 * runs behind the FIX-02 safety guards: an empty or incomplete server
 * response — nothing reported, less than half the known songs seen, or a
 * listed album whose fetch failed — skips pruning and is reported in the
 * outcome message instead of destroying the library.
 */
class SubsonicLibrarySyncer(
    private val database: OmnisonicDatabase,
    private val provider: MediaProvider,
    private val providerId: Long,
    private val traits: ProviderTraits = ProviderTraits(),
    private val runGate: ProviderRunGate = AllowAllRunGate,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Reports a typed server authentication rejection to the status tracker. */
    private val onAuthFailed: suspend (Long) -> Unit = {},
    /** Reports a completed provider call so a stale auth failure can clear. */
    private val onAuthSucceeded: suspend (Long) -> Unit = {},
) {

    private val runSequence = AtomicLong(0)

    /**
     * What the server reported during one library walk. Listed album ids are
     * recorded before their details are fetched, so a listed album whose
     * fetch failed still proves the server responded.
     */
    private class WalkSeen {
        val songIds = mutableSetOf<String>()
        val albumIds = mutableSetOf<String>()
        var albumFetchFailed = false
    }

    data class Outcome(
        val albumsProcessed: Int,
        val durationMs: Long,
        val message: String,
        /** Run-gate status that blocked the sync (offline, not on required Wi-Fi). */
        val blockedBy: ProviderStatus? = null,
        /** Provider traits report no library sync; nothing was read or written. */
        val skippedNoLibrarySync: Boolean = false,
    )

    suspend fun runFullSync(): Outcome = runSync(full = true)

    suspend fun runDiffSync(): Outcome = runSync(full = false)

    private suspend fun runSync(full: Boolean): Outcome {
        val started = nowMs()
        if (!traits.hasLibrarySync) {
            return Outcome(0, nowMs() - started, "skipped: provider does not report library sync", skippedNoLibrarySync = true)
        }
        val gate = runGate.check(providerId)
        if (gate != ProviderStatus.OK) {
            return Outcome(0, nowMs() - started, "blocked: $gate", blockedBy = gate)
        }
        return try {
            var albumsProcessed = 0
            val runId = nextRunId()
            val knownSongCount = if (full) database.songDao().selectCountByProvider(providerId) else 0
            val seen = WalkSeen()
            val watermark = if (full) null else diffWatermark()
            syncArtists(runId)
            albumsProcessed = syncAlbumsAndSongs(watermark, runId, seen)
            syncGenres()
            syncRadios()
            syncPlaylists(runId)
            syncBookmarks()
            var pruneSkippedReason: String? = null
            if (full) {
                pruneSkippedReason = prune(knownSongCount, seen, runId)
            }
            val message = pruneSkippedReason?.let { "prune skipped: $it" }
            database.withTransaction {
                recordStatus(full, started, albumsProcessed, resultOk = true, message = message)
            }
            runCatchingCancellable { onAuthSucceeded(providerId) }
            Outcome(albumsProcessed, nowMs() - started, message ?: "ok")
        } catch (cancellation: CancellationException) {
            // Cancellation is not a sync failure: no status row is recorded.
            throw cancellation
        } catch (authFailure: ProviderAuthException) {
            database.withTransaction {
                recordStatus(
                    full,
                    started,
                    0,
                    resultOk = false,
                    message = authFailure.message ?: authFailure.javaClass.simpleName,
                )
            }
            runCatchingCancellable { onAuthFailed(providerId) }
            throw authFailure
        } catch (failure: Exception) {
            database.withTransaction {
                recordStatus(
                    full,
                    started,
                    0,
                    resultOk = false,
                    message = failure.message ?: failure.javaClass.simpleName,
                )
            }
            throw failure
        }
    }

    private suspend fun diffWatermark(): Long? {
        val status = database.syncStatusDao().selectByProvider(providerId) ?: return null
        return listOfNotNull(status.lastDiffSyncAt, status.lastFullSyncAt).minOrNull()
    }

    /**
     * A run id must never repeat for stamps that may still exist in the
     * database, otherwise an unseen row could pass as seen (`!= :runId`).
     * The floor of every stamp this provider has written keeps ids above any
     * stored value across process restarts; the atomic sequence keeps
     * concurrent or repeated runs of this instance distinct.
     */
    private suspend fun nextRunId(): Long {
        val floor = maxOf(
            database.songDao().selectMaxLastSeenSyncId(providerId) ?: 0L,
            database.albumDao().selectMaxLastSeenSyncId(providerId) ?: 0L,
            database.artistDao().selectMaxLastSeenSyncId(providerId) ?: 0L,
            database.playlistDao().selectMaxLastSeenSyncId(providerId) ?: 0L,
        )
        return runSequence.updateAndGet { previous -> maxOf(previous, floor) + 1 }
    }

    private suspend fun syncArtists(runId: Long) {
        val dao = database.artistDao()
        val artists = provider.listArtists()
        val now = nowMs()
        database.withTransaction {
            artists.forEach { artist ->
                val existing = dao.selectByProviderAndExternal(providerId, artist.externalId)
                dao.upsertServerFields(
                    ArtistEntity(
                        providerId = providerId,
                        externalId = artist.externalId,
                        title = artist.title,
                        latinTitle = TextNormalizer.romanize(artist.title),
                        sortTitle = TextNormalizer.sortKey(artist.title),
                        imagePath = artist.imagePath ?: existing?.imagePath,
                        description = existing?.description,
                        playCount = existing?.playCount ?: 0,
                        lastPlayed = existing?.lastPlayed,
                        userRating = existing?.userRating,
                        isFavorite = existing?.isFavorite ?: false,
                        favoriteAt = existing?.favoriteAt,
                        serverRating = existing?.serverRating,
                        songCount = artist.songCount ?: existing?.songCount ?: 0,
                        albumCount = artist.albumCount ?: existing?.albumCount ?: 0,
                        updatedAt = now,
                    ),
                    lastSeenSyncId = runId,
                )
            }
        }
    }

    private suspend fun syncAlbumsAndSongs(
        watermarkMs: Long?,
        runId: Long,
        seen: WalkSeen,
    ): Int {
        var processed = 0
        var offset = 0
        val pageSize = ProviderAlbumRequest.DEFAULT_PAGE_SIZE
        while (true) {
            val page = provider.listAlbums(
                ProviderAlbumRequest(
                    type = if (watermarkMs == null) AlbumListType.ALPHABETICAL_BY_NAME else AlbumListType.NEWEST,
                    size = pageSize,
                    offset = offset,
                ),
            )
            if (page.isEmpty()) break
            val candidates = if (watermarkMs == null) {
                page
            } else {
                page.filter { album ->
                    val created = album.createdAtMs
                    created == null || created >= watermarkMs
                }
            }
            candidates.forEach { listed ->
                if (syncAlbumWithSongs(listed.externalId, runId, seen)) processed += 1
            }
            if (watermarkMs != null && page.all { album ->
                    val created = album.createdAtMs
                    created != null && created < watermarkMs
                }
            ) {
                break
            }
            if (page.size < pageSize) break
            offset += page.size
        }
        return processed
    }

    private suspend fun syncAlbumWithSongs(
        albumExternalId: String,
        runId: Long,
        seen: WalkSeen,
    ): Boolean {
        seen.albumIds += albumExternalId
        val details = provider.getAlbum(albumExternalId)
        if (details == null) {
            seen.albumFetchFailed = true
            return false
        }
        val now = nowMs()
        database.withTransaction {
            val albumDao = database.albumDao()
            val songDao = database.songDao()
            val artistDao = database.artistDao()

            val existingAlbum = albumDao.selectByProviderAndExternal(providerId, details.album.externalId)
            val albumId = albumDao.upsertServerFields(
                AlbumEntity(
                    providerId = providerId,
                    externalId = details.album.externalId,
                    title = details.album.title,
                    latinTitle = TextNormalizer.romanize(details.album.title),
                    sortTitle = TextNormalizer.sortKey(details.album.title),
                    displayArtist = details.album.artistName ?: existingAlbum?.displayArtist,
                    year = details.album.year ?: existingAlbum?.year,
                    imagePath = details.album.imagePath ?: existingAlbum?.imagePath,
                    durationMs = details.album.durationMs ?: existingAlbum?.durationMs,
                    songCount = details.album.songCount ?: existingAlbum?.songCount ?: 0,
                    genresJson = if (details.album.genres.isNotEmpty()) {
                        JsonText.encodeStringList(details.album.genres)
                    } else {
                        existingAlbum?.genresJson
                    },
                    isCompilation = existingAlbum?.isCompilation ?: false,
                    playCount = existingAlbum?.playCount ?: 0,
                    lastPlayed = existingAlbum?.lastPlayed,
                    userRating = existingAlbum?.userRating,
                    isFavorite = existingAlbum?.isFavorite ?: false,
                    favoriteAt = existingAlbum?.favoriteAt,
                    serverRating = existingAlbum?.serverRating,
                    resumeTrackPath = existingAlbum?.resumeTrackPath,
                    isAudiobook = existingAlbum?.isAudiobook ?: false,
                    description = existingAlbum?.description,
                    discNamesJson = existingAlbum?.discNamesJson,
                    updatedAt = now,
                ),
                lastSeenSyncId = runId,
            )

            details.album.artistExternalId?.let { artistExternalId ->
                artistDao.selectByProviderAndExternal(providerId, artistExternalId)?.let { artist ->
                    database.albumArtistDao().upsert(
                        AlbumArtistEntity(providerId = providerId, albumId = albumId, artistId = artist.id),
                    )
                }
            }

            details.songs.forEach { song ->
                seen.songIds += song.externalId
                val existing = songDao.selectByProviderAndExternal(providerId, song.externalId)
                val songId = songDao.upsertServerFields(
                    SongEntity(
                        providerId = providerId,
                        externalId = song.externalId,
                        albumId = albumId,
                        title = song.title,
                        latinTitle = TextNormalizer.romanize(song.title),
                        sortTitle = TextNormalizer.sortKey(song.title),
                        displayArtist = song.artistName ?: existing?.displayArtist,
                        filePath = song.filePath ?: existing?.filePath,
                        durationMs = song.durationMs ?: existing?.durationMs,
                        trackNo = song.trackNo ?: existing?.trackNo,
                        discNo = song.discNo ?: existing?.discNo,
                        year = song.year ?: existing?.year,
                        genresJson = song.genre?.let { JsonText.encodeStringList(listOf(it)) } ?: existing?.genresJson,
                        imagePath = song.imagePath ?: existing?.imagePath,
                        playCount = existing?.playCount ?: 0,
                        lastPlayed = existing?.lastPlayed,
                        skipped = existing?.skipped ?: false,
                        lastSkipped = existing?.lastSkipped,
                        userRating = existing?.userRating,
                        isFavorite = existing?.isFavorite ?: false,
                        favoriteAt = existing?.favoriteAt,
                        serverRating = existing?.serverRating,
                        resumeMs = existing?.resumeMs,
                        resumeUpdatedAt = existing?.resumeUpdatedAt,
                        missingOnServer = existing?.missingOnServer ?: false,
                        replayGainTrack = existing?.replayGainTrack,
                        replayGainAlbum = existing?.replayGainAlbum,
                        replayGainType = existing?.replayGainType,
                        windowStartMs = existing?.windowStartMs,
                        windowEndMs = existing?.windowEndMs,
                        isAudiobook = existing?.isAudiobook ?: false,
                        codec = song.codec ?: existing?.codec,
                        bitrateKbps = song.bitrateKbps ?: existing?.bitrateKbps,
                        sampleRate = existing?.sampleRate,
                        bitDepth = existing?.bitDepth,
                        channelCount = existing?.channelCount,
                        updatedAt = now,
                    ),
                    lastSeenSyncId = runId,
                )
                song.artistExternalId?.let { artistExternalId ->
                    artistDao.selectByProviderAndExternal(providerId, artistExternalId)?.let { artist ->
                        database.songArtistDao().upsert(
                            SongArtistEntity(providerId = providerId, songId = songId, artistId = artist.id),
                        )
                    }
                }
            }
        }
        return true
    }

    private suspend fun syncGenres() {
        val dao = database.genreDao()
        val genres = provider.listGenres()
        val now = nowMs()
        database.withTransaction {
            genres.forEach { genre ->
                val existing = dao.selectByProviderAndExternal(providerId, genre.externalId)
                dao.upsert(
                    GenreEntity(
                        id = existing?.id ?: 0,
                        providerId = providerId,
                        externalId = genre.externalId,
                        title = genre.externalId,
                        songCount = genre.songCount ?: existing?.songCount ?: 0,
                        albumCount = genre.albumCount ?: existing?.albumCount ?: 0,
                        playCount = existing?.playCount ?: 0,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    private suspend fun syncRadios() {
        val dao = database.internetRadioDao()
        val radios = provider.listRadios()
        val now = nowMs()
        database.withTransaction {
            radios.forEach { radio ->
                val existing = dao.selectByProviderAndExternal(providerId, radio.externalId)
                dao.upsert(
                    InternetRadioEntity(
                        id = existing?.id ?: 0,
                        providerId = providerId,
                        externalId = radio.externalId,
                        title = radio.name,
                        streamUrl = radio.streamUrl,
                        isFavorite = existing?.isFavorite ?: false,
                        favoriteAt = existing?.favoriteAt,
                        lastPlayed = existing?.lastPlayed,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    private suspend fun syncPlaylists(runId: Long) {
        val playlistDao = database.playlistDao()
        val entryDao = database.playlistEntryDao()
        val songDao = database.songDao()
        val summaries = provider.listPlaylists()
        val now = nowMs()
        database.withTransaction {
            summaries.forEach { summary ->
                val existing = playlistDao.selectByProviderAndExternal(providerId, summary.externalId)
                val playlistId = playlistDao.upsert(
                    PlaylistEntity(
                        id = existing?.id ?: 0,
                        providerId = providerId,
                        externalId = summary.externalId,
                        title = summary.name,
                        type = existing?.type ?: 0,
                        isFavorite = existing?.isFavorite ?: false,
                        favoriteAt = existing?.favoriteAt,
                        autoSync = existing?.autoSync ?: false,
                        autoOffline = existing?.autoOffline ?: false,
                        entryCount = summary.songCount ?: existing?.entryCount ?: 0,
                        durationMs = summary.durationMs ?: existing?.durationMs,
                        lastModified = summary.changedAtMs ?: existing?.lastModified,
                        lastPlayed = existing?.lastPlayed,
                        hidden = existing?.hidden ?: false,
                        lastSeenSyncId = runId,
                        updatedAt = now,
                    ),
                )

                val details = provider.getPlaylist(summary.externalId) ?: return@forEach
                entryDao.deleteByPlaylist(playlistId)
                entryDao.upsertAll(
                    details.entries.mapIndexed { order, entry ->
                        PlaylistEntryEntity(
                            playlistId = playlistId,
                            providerId = providerId,
                            position = order,
                            externalId = entry.externalId,
                            songId = songDao.selectByProviderAndExternal(providerId, entry.externalId)?.id,
                            title = entry.title,
                            displayArtist = entry.artistName,
                            durationMs = entry.durationMs,
                            imagePath = entry.imagePath,
                            addedAt = now,
                        )
                    },
                )
                playlistDao.recountEntries(playlistId)
            }
        }
    }

    private suspend fun syncBookmarks() {
        val songDao = database.songDao()
        val outboxDao = database.outboxDao()
        val bookmarks = provider.listBookmarks()
        database.withTransaction {
            bookmarks.forEach { bookmark ->
                val song = songDao.selectByProviderAndExternal(providerId, bookmark.entryExternalId) ?: return@forEach
                val pendingResume = outboxDao.countPendingByExternalAndAction(
                    providerId,
                    bookmark.entryExternalId,
                    OutboxActions.RESUME_POINT,
                ) > 0
                val serverUpdatedAt = bookmark.updatedAtMs
                if (pendingResume || serverUpdatedAt == null) return@forEach
                if (serverUpdatedAt <= (song.resumeUpdatedAt ?: 0L)) return@forEach
                songDao.applyServerResumePoint(song.id, bookmark.positionMs, serverUpdatedAt)
            }
        }
    }

    /**
     * Full-sync mark-and-sweep behind the FIX-02 safety guards. Returns the
     * skip reason when pruning did not run, null when it did.
     *
     * Guards (in order): the walk must have reported something at all; the
     * songs it reported must reach at least half of the count recorded before
     * the walk (a smaller response looks like a broken or partial listing,
     * not a genuine shrink); and no listed album may have failed to load, or
     * the songs hidden behind that failure would be treated as vanished. The
     * half-guard counts songs only, while the emptiness guard accepts any
     * reported id (songs and listed albums), so an all-failed walk against a
     * responding server is caught by the fetch-failure guard with an accurate
     * reason.
     */
    private suspend fun prune(knownSongCount: Int, seen: WalkSeen, runId: Long): String? {
        if (seen.songIds.isEmpty() && seen.albumIds.isEmpty()) {
            return "server reported no albums or songs"
        }
        if (seen.songIds.size * 2 < knownSongCount) {
            return "server reported ${seen.songIds.size} of $knownSongCount known songs"
        }
        if (seen.albumFetchFailed) {
            return "album details unavailable for a listed album"
        }
        database.withTransaction {
            val songDao = database.songDao()
            songDao.tombstoneUnseen(providerId, runId)
            val deletableSongIds = songDao.selectUnseenDeletableIds(providerId, runId)
            database.chapterDao().deleteBySongIds(deletableSongIds)
            songDao.deleteByIds(deletableSongIds)
            database.songArtistDao().deleteBySongIds(deletableSongIds)

            val albumDao = database.albumDao()
            val deletableAlbumIds = albumDao.selectUnseenWithoutSongsIds(providerId, runId)
            database.chapterDao().deleteByAlbumIds(deletableAlbumIds)
            database.albumArtistDao().deleteByAlbumIds(deletableAlbumIds)
            albumDao.deleteByIds(deletableAlbumIds)

            database.playlistDao().deleteUnseen(providerId, runId)
            database.playlistEntryDao().deleteOrphanedEntries()

            database.artistDao().deleteUnseenWithoutLinks(providerId, runId)
        }
        return null
    }

    private suspend fun recordStatus(full: Boolean, startedAt: Long, count: Int, resultOk: Boolean, message: String?) {
        val dao = database.syncStatusDao()
        val existing = dao.selectByProvider(providerId)
        dao.upsert(
            (existing ?: SyncStatusEntity(providerId = providerId)).copy(
                lastFullSyncAt = if (full && resultOk) startedAt else existing?.lastFullSyncAt,
                lastDiffSyncAt = if (!full && resultOk) startedAt else existing?.lastDiffSyncAt,
                lastSyncResult = if (resultOk) RESULT_OK else RESULT_FAILED,
                lastSyncMessage = message,
                lastSyncCount = count,
                lastSyncDurationMs = nowMs() - startedAt,
            ),
        )
    }

    companion object {
        const val RESULT_OK = 1
        const val RESULT_FAILED = 2
    }
}
