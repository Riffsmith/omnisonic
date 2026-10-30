package app.omnisonic.core.data.sync

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
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
import app.omnisonic.core.data.json.JsonText
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderAlbumRequest

/**
 * Server-to-device library walk for Subsonic-compatible providers. Server
 * fields never overwrite locally-owned playback state (play counts, favorites,
 * ratings, resume points, replay gain, clips): existing rows are merged before
 * the REPLACE upsert, keyed on (providerId, externalId).
 *
 * Full sync pages getAlbumList2 alphabetically; diff sync pages newest-first
 * and stops once a whole page predates the last-sync watermark.
 */
class SubsonicLibrarySyncer(
    private val database: OmnisonicDatabase,
    private val provider: MediaProvider,
    private val providerId: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    data class Outcome(
        val albumsProcessed: Int,
        val durationMs: Long,
        val message: String,
    )

    suspend fun runFullSync(): Outcome = runSync(full = true)

    suspend fun runDiffSync(): Outcome = runSync(full = false)

    private suspend fun runSync(full: Boolean): Outcome {
        val started = nowMs()
        return try {
            var albumsProcessed = 0
            database.withTransaction {
                val watermark = if (full) null else diffWatermark()
                syncArtists()
                albumsProcessed = syncAlbumsAndSongs(watermark)
                syncGenres()
                syncRadios()
                syncPlaylists()
                syncBookmarks()
                recordStatus(full, started, albumsProcessed, resultOk = true, message = null)
            }
            Outcome(albumsProcessed, nowMs() - started, "ok")
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

    private suspend fun syncArtists() {
        val dao = database.artistDao()
        provider.listArtists().forEach { artist ->
            val existing = dao.selectByProviderAndExternal(providerId, artist.externalId)
            dao.upsert(
                ArtistEntity(
                    id = existing?.id ?: 0,
                    providerId = providerId,
                    externalId = artist.externalId,
                    title = artist.title,
                    imagePath = artist.imagePath ?: existing?.imagePath,
                    playCount = existing?.playCount ?: 0,
                    lastPlayed = existing?.lastPlayed,
                    userRating = existing?.userRating,
                    isFavorite = existing?.isFavorite ?: false,
                    favoriteAt = existing?.favoriteAt,
                    serverRating = existing?.serverRating,
                    songCount = artist.songCount ?: existing?.songCount ?: 0,
                    albumCount = artist.albumCount ?: existing?.albumCount ?: 0,
                    updatedAt = nowMs(),
                ),
            )
        }
    }

    private suspend fun syncAlbumsAndSongs(watermarkMs: Long?): Int {
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
                if (syncAlbumWithSongs(listed.externalId)) processed += 1
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

    private suspend fun syncAlbumWithSongs(albumExternalId: String): Boolean {
        val details = provider.albumDetails(albumExternalId) ?: return false
        val albumDao = database.albumDao()
        val songDao = database.songDao()
        val artistDao = database.artistDao()
        val now = nowMs()

        val existingAlbum = albumDao.selectByProviderAndExternal(providerId, details.externalId)
        val albumId = albumDao.upsert(
            AlbumEntity(
                id = existingAlbum?.id ?: 0,
                providerId = providerId,
                externalId = details.externalId,
                title = details.title,
                displayArtist = details.artistName ?: existingAlbum?.displayArtist,
                year = details.year ?: existingAlbum?.year,
                imagePath = details.imagePath ?: existingAlbum?.imagePath,
                durationMs = details.durationMs ?: existingAlbum?.durationMs,
                songCount = details.songCount ?: existingAlbum?.songCount ?: 0,
                genresJson = if (details.genres.isNotEmpty()) {
                    JsonText.encodeStringList(details.genres)
                } else {
                    existingAlbum?.genresJson
                },
                playCount = existingAlbum?.playCount ?: 0,
                lastPlayed = existingAlbum?.lastPlayed,
                userRating = existingAlbum?.userRating,
                isFavorite = existingAlbum?.isFavorite ?: false,
                favoriteAt = existingAlbum?.favoriteAt,
                serverRating = existingAlbum?.serverRating,
                updatedAt = now,
            ),
        )

        details.artistExternalId?.let { artistExternalId ->
            artistDao.selectByProviderAndExternal(providerId, artistExternalId)?.let { artist ->
                database.albumArtistDao().upsert(
                    AlbumArtistEntity(providerId = providerId, albumId = albumId, artistId = artist.id),
                )
            }
        }

        provider.browseDirectory(details.externalId).childSongs.forEach { song ->
            val existing = songDao.selectByProviderAndExternal(providerId, song.externalId)
            val songId = songDao.upsert(
                SongEntity(
                    id = existing?.id ?: 0,
                    providerId = providerId,
                    externalId = song.externalId,
                    albumId = albumId,
                    title = song.title,
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
                    resumePointMs = existing?.resumePointMs,
                    replayGainTrack = existing?.replayGainTrack,
                    replayGainAlbum = existing?.replayGainAlbum,
                    replayGainType = existing?.replayGainType,
                    clipStartMs = existing?.clipStartMs,
                    clipEndMs = existing?.clipEndMs,
                    isAudiobook = existing?.isAudiobook ?: false,
                    codec = song.codec ?: existing?.codec,
                    bitrateKbps = song.bitrateKbps ?: existing?.bitrateKbps,
                    sampleRate = existing?.sampleRate,
                    bitDepth = existing?.bitDepth,
                    channelCount = existing?.channelCount,
                    updatedAt = now,
                ),
            )
            song.artistExternalId?.let { artistExternalId ->
                artistDao.selectByProviderAndExternal(providerId, artistExternalId)?.let { artist ->
                    database.songArtistDao().upsert(
                        SongArtistEntity(providerId = providerId, songId = songId, artistId = artist.id),
                    )
                }
            }
        }
        return true
    }

    private suspend fun syncGenres() {
        val dao = database.genreDao()
        provider.listGenres().forEach { genre ->
            val existing = dao.selectByProvider(providerId).firstOrNull { it.externalId == genre.externalId }
            dao.upsert(
                GenreEntity(
                    id = existing?.id ?: 0,
                    providerId = providerId,
                    externalId = genre.externalId,
                    title = genre.externalId,
                    songCount = genre.songCount ?: existing?.songCount ?: 0,
                    albumCount = genre.albumCount ?: existing?.albumCount ?: 0,
                    playCount = existing?.playCount ?: 0,
                    updatedAt = nowMs(),
                ),
            )
        }
    }

    private suspend fun syncRadios() {
        val dao = database.internetRadioDao()
        provider.listRadios().forEach { radio ->
            val existing = dao.selectByProvider(providerId).firstOrNull { it.externalId == radio.externalId }
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
                    updatedAt = nowMs(),
                ),
            )
        }
    }

    private suspend fun syncPlaylists() {
        val playlistDao = database.playlistDao()
        val entryDao = database.playlistEntryDao()
        val songDao = database.songDao()
        val now = nowMs()
        provider.listPlaylists().forEach { summary ->
            val existing = playlistDao.selectByProvider(providerId).firstOrNull { it.externalId == summary.externalId }
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
                        entryOrder = order,
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

    private suspend fun syncBookmarks() {
        val songDao = database.songDao()
        provider.listBookmarks().forEach { bookmark ->
            val song = songDao.selectByProviderAndExternal(providerId, bookmark.entryExternalId) ?: return@forEach
            songDao.applyProviderResumePoint(song.id, bookmark.positionMs)
        }
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
