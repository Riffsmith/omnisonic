package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.provider.api.DownloadEnqueueResult
import kotlinx.coroutines.flow.first

/** One user-requested download, before the queue assigns a row. */
data class DownloadUserEnqueue(
    val providerId: Long,
    val externalId: String,
    val title: String?,
    val imagePath: String?,
    val destination: DownloadDestination,
)

/**
 * The one process-wide enqueue seam. The foreground runtime implements it so
 * library and workspace actions share the coordinator, global-pause lock and
 * production run gate instead of constructing a second queue owner.
 */
fun interface DownloadEnqueuePort {
    suspend fun enqueue(request: DownloadUserEnqueue): DownloadEnqueueResult
}

/** Outcome counts of one multi-song user action; a batch is never atomic by design. */
data class LibraryDownloadResult(
    val requested: Int = 0,
    val queued: Int = 0,
    val blocked: Int = 0,
    val unsupported: Int = 0,
    val destinationConflicts: Int = 0,
) {
    val failed: Int get() = blocked + unsupported + destinationConflicts
}

enum class LibraryRuleToggle { ADDED, REMOVED, INVALID }

/**
 * Song/album/playlist keep-offline and export actions over the existing
 * enqueuer (Phase 7 task 9c). Catalog reads deliberately use
 * [ProfileScope.Unrestricted]: the caller acts on a row the user already sees,
 * and a partially loaded detail window must never silently enqueue a subset.
 * Every call returns typed counts instead of throwing for ordinary gate,
 * capability or destination refusals. The remembered destination is persisted
 * only when the caller made an explicit choice.
 */
class LibraryDownloadActions(
    private val db: OmnisonicDatabase,
    private val enqueuePort: DownloadEnqueuePort,
    private val rules: AutomaticRuleRepository,
    private val settings: DownloadQueueSettingsStore,
    private val workStart: DownloadWorkStart,
) {

    suspend fun preferredDestination(): DownloadDestination = settings.preferredDestination.first()

    suspend fun enqueueSong(
        songId: Long,
        destination: DownloadDestination? = null,
    ): LibraryDownloadResult {
        val song = db.songDao().selectById(songId) ?: return LibraryDownloadResult()
        return enqueueSongs(listOf(song), destination)
    }

    suspend fun enqueueAlbum(
        albumId: Long,
        destination: DownloadDestination? = null,
    ): LibraryDownloadResult {
        val album = db.albumDao().selectById(albumId) ?: return LibraryDownloadResult()
        return enqueueSongs(readAlbumSongs(album), destination)
    }

    suspend fun enqueuePlaylist(
        playlistId: Long,
        destination: DownloadDestination? = null,
    ): LibraryDownloadResult {
        val playlist = db.playlistDao().selectById(playlistId) ?: return LibraryDownloadResult()
        return enqueueSongs(readPlaylistSongs(playlist), destination)
    }

    suspend fun toggleFavoritesRule(providerId: Long): LibraryRuleToggle =
        toggleRule(providerId, AutomaticRuleKind.FAVORITES, AutomaticRuleRepository.FAVORITES_TARGET_EXTERNAL_ID)

    suspend fun toggleAlbumRule(providerId: Long, targetExternalId: String): LibraryRuleToggle =
        toggleRule(providerId, AutomaticRuleKind.ALBUM, targetExternalId)

    suspend fun togglePlaylistRule(providerId: Long, targetExternalId: String): LibraryRuleToggle =
        toggleRule(providerId, AutomaticRuleKind.PLAYLIST, targetExternalId)

    private suspend fun toggleRule(
        providerId: Long,
        kind: AutomaticRuleKind,
        targetExternalId: String,
    ): LibraryRuleToggle {
        val existing = rules.find(providerId, kind, targetExternalId, ProfileScope.Unrestricted)
        val outcome = if (existing == null) {
            when (rules.add(providerId, kind, targetExternalId)) {
                AutomaticRuleAddOutcome.ADDED, AutomaticRuleAddOutcome.DUPLICATE ->
                    LibraryRuleToggle.ADDED
                AutomaticRuleAddOutcome.INVALID -> LibraryRuleToggle.INVALID
            }
        } else {
            if (rules.remove(providerId, kind, targetExternalId)) {
                LibraryRuleToggle.REMOVED
            } else {
                LibraryRuleToggle.INVALID
            }
        }
        if (outcome != LibraryRuleToggle.INVALID) workStart.requestStart()
        return outcome
    }

    private suspend fun enqueueSongs(
        songs: List<SongEntity>,
        destination: DownloadDestination?,
    ): LibraryDownloadResult {
        if (songs.isEmpty()) return LibraryDownloadResult()
        val target = destination ?: settings.preferredDestination.first()
        if (destination != null) settings.setPreferredDestination(destination)
        var queued = 0
        var blocked = 0
        var unsupported = 0
        var conflicts = 0
        for (song in songs) {
            val externalId = song.externalId?.takeIf { it.isNotBlank() } ?: continue
            when (
                enqueuePort.enqueue(
                    DownloadUserEnqueue(
                        providerId = song.providerId,
                        externalId = externalId,
                        title = song.title,
                        imagePath = song.imagePath,
                        destination = target,
                    ),
                )
            ) {
                DownloadEnqueueResult.Enqueued -> queued++
                is DownloadEnqueueResult.BlockedByStatus -> blocked++
                DownloadEnqueueResult.SkippedDownloadUnsupported -> unsupported++
                is DownloadEnqueueResult.NeedsDestinationChange -> conflicts++
            }
        }
        if (queued > 0) workStart.requestStart()
        return LibraryDownloadResult(
            requested = songs.size,
            queued = queued,
            blocked = blocked,
            unsupported = unsupported,
            destinationConflicts = conflicts,
        )
    }

    private suspend fun readAlbumSongs(album: AlbumEntity): List<SongEntity> {
        val songs = ArrayList<SongEntity>()
        var offset = 0
        while (true) {
            val page = db.songDao().albumTracks(ProfileScope.Unrestricted, album.id, PAGE_SIZE, offset)
            songs += page
            if (page.size < PAGE_SIZE) return songs
            offset += page.size
        }
    }

    private suspend fun readPlaylistSongs(playlist: PlaylistEntity): List<SongEntity> {
        val songs = ArrayList<SongEntity>()
        var offset = 0
        while (true) {
            val page = db.playlistEntryDao()
                .detailEntries(ProfileScope.Unrestricted, playlist.id, PAGE_SIZE, offset)
            for (entry in page) {
                val song = entry.songId?.let { db.songDao().selectById(it) }
                if (song != null && song.externalId?.isNotBlank() == true) {
                    songs += song
                } else {
                    val externalId = entry.externalId?.takeIf { it.isNotBlank() } ?: continue
                    songs += SongEntity(
                        id = 0,
                        providerId = entry.providerId,
                        externalId = externalId,
                        title = entry.title,
                        imagePath = entry.imagePath,
                        updatedAt = 0L,
                    )
                }
            }
            if (page.size < PAGE_SIZE) return songs
            offset += page.size
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
