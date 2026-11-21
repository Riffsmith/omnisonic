package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadWorkspaceRepositoryTest : DatabaseTestBase() {

    private fun repository() = DownloadWorkspaceRepository(db)

    private suspend fun seedRule(providerId: Long, externalId: String = "alb-1") {
        db.downloadRuleDao().insertIgnoring(
            DownloadRuleEntity(
                providerId = providerId,
                kind = AutomaticRuleKind.ALBUM.name,
                targetExternalId = externalId,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
    }

    private suspend fun seedNamedPlaylist(providerId: Long, externalId: String?, title: String) {
        playlistDao.upsert(
            PlaylistEntity(
                id = 0,
                providerId = providerId,
                externalId = externalId,
                title = title,
                type = 0,
                updatedAt = 1_000,
            ),
        )
    }

    @Test
    fun `every live workspace read excludes a hidden provider`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        seedDownloadItem(providerId = 1, mediaKey = "key-1")
        seedDownloadItem(providerId = 2, mediaKey = "key-2")
        seedOfflineFile(providerId = 1)
        offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 2,
                mediaType = "track",
                sourceKey = "src-key-2",
                offlinePath = "/cache/key-2",
            ),
        )
        seedRule(providerId = 1)
        seedRule(providerId = 2)
        val hidden = ProfileScope().excluding(listOf(2L))
        val workspace = repository()

        assertThat(workspace.queue(hidden).first().map { it.providerId }).containsExactly(1L)
        assertThat(workspace.offline(hidden).first().map { it.providerId }).containsExactly(1L)
        assertThat(workspace.rules(hidden).first().map { it.providerId }).containsExactly(1L)
        assertThat(workspace.queue(ProfileScope.Unrestricted).first()).hasSize(2)
        assertThat(workspace.offline(ProfileScope.Unrestricted).first()).hasSize(2)
        assertThat(workspace.rules(ProfileScope.Unrestricted).first()).hasSize(2)
    }

    @Test
    fun `pin and unpin rewrite only the offline ownership flags`() = runDbTest {
        newProvider(id = 1)
        val offline = seedOfflineFile(providerId = 1)
        seedRule(providerId = 1)
        db.offlineFileDao().upsert(
            offline.copy(tempCache = true, autoRule = "1"),
        )

        assertThat(repository().setManualOwnership(offline.id, permanent = true)).isTrue()

        val pinned = offlineFileDao.selectById(offline.id)!!
        assertThat(pinned.tempCache).isFalse()
        assertThat(pinned.autoRule).isNull()
        assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()

        assertThat(repository().setManualOwnership(offline.id, permanent = false)).isTrue()
        val unpinned = offlineFileDao.selectById(offline.id)!!
        assertThat(unpinned.tempCache).isTrue()
        assertThat(unpinned.autoRule).isNull()
        assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()
    }

    @Test
    fun `pin reports a missing row instead of throwing`() = runDbTest {
        newProvider(id = 1)

        assertThat(repository().setManualOwnership(999, permanent = true)).isFalse()
    }

    @Test
    fun `rule candidates are scoped and skip blank external ids`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        seedNamedPlaylist(1, "pl-1", "Visible playlist")
        seedNamedPlaylist(1, null, "No external id")
        seedNamedPlaylist(2, "pl-hidden", "Hidden playlist")
        newAlbum(1, 1)
        newAlbum(2, 2)

        val candidates = repository().ruleCandidates(ProfileScope().excluding(listOf(2L)))

        assertThat(candidates.playlists.map { it.title }).containsExactly("Visible playlist")
        assertThat(candidates.albums.map { it.title }).containsExactly("album-1")
        assertThat(candidates.playlists.single().providerId).isEqualTo(1L)
        assertThat(candidates.albums.single().externalId).isEqualTo("alb-1")
    }
}
