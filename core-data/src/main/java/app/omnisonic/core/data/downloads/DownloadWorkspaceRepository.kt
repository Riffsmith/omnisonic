package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import kotlinx.coroutines.flow.Flow

/** One addable playlist/album rule target, already resolved for its provider. */
data class DownloadRuleTarget(
    val providerId: Long,
    val externalId: String,
    val title: String,
)

/** Catalog targets offered by the rules surface; both lists are profile-scoped reads. */
data class DownloadRuleCandidates(
    val playlists: List<DownloadRuleTarget>,
    val albums: List<DownloadRuleTarget>,
)

/**
 * Read/command surface of the downloads workspace (Phase 7 task 9a/9b). Every
 * live read takes [ProfileScope] with the Raw+wrapper DAO convention, so a
 * hidden provider never appears on the surface while internal workers keep
 * their unrestricted reads. Pin/unpin writes only the offline row's retained
 * ownership flags; it never enqueues or looks at the queue.
 */
class DownloadWorkspaceRepository(
    private val db: OmnisonicDatabase,
    private val ownership: OfflineOwnershipGuard = OfflineOwnershipGuard(),
) {

    fun queue(scope: ProfileScope): Flow<List<DownloadItemEntity>> =
        db.downloadDao().observeAll(scope)

    fun offline(scope: ProfileScope): Flow<List<OfflineFileEntity>> =
        db.offlineFileDao().observeAll(scope)

    fun rules(scope: ProfileScope): Flow<List<DownloadRuleEntity>> =
        db.downloadRuleDao().observeAll(scope)

    /** Provider identities for row subtitles; hidden filtering happens in the UI scope. */
    val providers: Flow<List<ProviderIdentityRow>> = db.providerDao().observeIdentities()

    /**
     * Manual ownership conversion. [permanent] true stores permanent manual
     * media; false stores rolling manual media. Both clear the automatic-rule
     * hint, so the next verified prune pass protects the row instead of
     * treating a stale rule id as ownership.
     */
    suspend fun setManualOwnership(offlineId: Long, permanent: Boolean): Boolean =
        ownership.withOwnershipLock {
            db.offlineFileDao().setManualOwnership(offlineId, tempCache = !permanent) == 1
        }

    /**
     * Addable targets for the rules surface. Albums and playlists are read with
     * the real UI scope; their titles are only used to label an add action.
     */
    suspend fun ruleCandidates(scope: ProfileScope): DownloadRuleCandidates {
        val playlists = db.playlistDao()
            .browsePlaylists(scope, limit = CANDIDATE_LIMIT, offset = 0)
            .mapNotNull { playlist ->
                playlist.externalId?.takeIf { it.isNotBlank() }?.let {
                    DownloadRuleTarget(playlist.providerId, it, playlist.title)
                }
            }
        val albums = db.albumDao()
            .browseAlbums(scope, limit = CANDIDATE_LIMIT, offset = 0)
            .mapNotNull { album ->
                album.externalId?.takeIf { it.isNotBlank() }?.let {
                    DownloadRuleTarget(album.providerId, it, album.title)
                }
            }
        return DownloadRuleCandidates(playlists = playlists, albums = albums)
    }

    private companion object {
        /** Bounded picker size; the rules surface is not a paged catalog browser. */
        const val CANDIDATE_LIMIT = 200
    }
}
