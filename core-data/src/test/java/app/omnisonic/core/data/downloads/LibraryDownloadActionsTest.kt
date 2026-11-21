package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.provider.api.DownloadEnqueueResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDownloadActionsTest : DatabaseTestBase() {

    private val requests = mutableListOf<DownloadUserEnqueue>()
    private var starts = 0

    private fun actions(
        settings: DownloadQueueSettingsStore = DownloadQueueSettingsStore.inMemory(),
        result: (DownloadUserEnqueue) -> DownloadEnqueueResult = { DownloadEnqueueResult.Enqueued },
    ) = LibraryDownloadActions(
        db = db,
        enqueuePort = DownloadEnqueuePort { request ->
            requests += request
            result(request)
        },
        rules = AutomaticRuleRepository(db),
        settings = settings,
        workStart = DownloadWorkStart { starts++ },
    )

    @Test
    fun `an explicit album action queues every track for the chosen destination and remembers it`() = runDbTest {
        newProvider(id = 1)
        val album = newAlbum(1, 1).let { albumDao.upsert(it.copy(songCount = 2)); albumDao.selectById(1)!! }
        newSong(1, 1, albumId = album.id)
        newSong(2, 1, albumId = album.id)
        val settings = DownloadQueueSettingsStore.inMemory()
        val actions = actions(settings = settings)

        val result = actions.enqueueAlbum(album.id, DownloadDestination.EXPORT)

        assertThat(result.requested).isEqualTo(2)
        assertThat(result.queued).isEqualTo(2)
        assertThat(requests.map { it.externalId }).containsExactly("song-1", "song-2")
        assertThat(requests.map { it.destination }).containsExactly(
            DownloadDestination.EXPORT,
            DownloadDestination.EXPORT,
        )
        assertThat(settings.preferredDestination.first()).isEqualTo(DownloadDestination.EXPORT)
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `the remembered destination is used without rewriting it`() = runDbTest {
        newProvider(id = 1)
        val song = newSong(7, 1)
        val settings = DownloadQueueSettingsStore.inMemory()
        settings.setPreferredDestination(DownloadDestination.OFFLINE)
        val actions = actions(settings = settings)

        val result = actions.enqueueSong(song.id)

        assertThat(result.queued).isEqualTo(1)
        assertThat(requests.single().destination).isEqualTo(DownloadDestination.OFFLINE)
        assertThat(settings.preferredDestination.first()).isEqualTo(DownloadDestination.OFFLINE)
    }

    @Test
    fun `gate and capability refusals are counted rather than thrown`() = runDbTest {
        newProvider(id = 1)
        val album = newAlbum(1, 1).let { albumDao.upsert(it.copy(songCount = 3)); albumDao.selectById(1)!! }
        newSong(1, 1, albumId = album.id)
        newSong(2, 1, albumId = album.id)
        newSong(3, 1, albumId = album.id)
        val outcomes = ArrayDeque(
            listOf(
                DownloadEnqueueResult.SkippedDownloadUnsupported,
                DownloadEnqueueResult.NeedsDestinationChange("existing export"),
                DownloadEnqueueResult.BlockedByStatus(app.omnisonic.provider.api.ProviderStatus.OFFLINE),
            ),
        )
        val actions = actions(result = { outcomes.removeFirst() })

        val result = actions.enqueueAlbum(album.id)

        assertThat(result.requested).isEqualTo(3)
        assertThat(result.queued).isEqualTo(0)
        assertThat(result.unsupported).isEqualTo(1)
        assertThat(result.destinationConflicts).isEqualTo(1)
        assertThat(result.blocked).isEqualTo(1)
        assertThat(result.failed).isEqualTo(3)
        assertThat(starts).isEqualTo(0)
    }

    @Test
    fun `playlist actions fall back to entry metadata when no song row is linked`() = runDbTest {
        newProvider(id = 1)
        val playlist = seedPlaylist(providerId = 1)
        playlistEntryDao.upsert(
            PlaylistEntryEntity(
                playlistId = playlist.id,
                providerId = 1,
                position = 0,
                externalId = "ghost",
                title = "Ghost entry",
                addedAt = 1_000,
            ),
        )
        val actions = actions()

        val result = actions.enqueuePlaylist(playlist.id, DownloadDestination.OFFLINE)

        assertThat(result.queued).isEqualTo(1)
        assertThat(requests.single().externalId).isEqualTo("ghost")
        assertThat(requests.single().title).isEqualTo("Ghost entry")
    }

    @Test
    fun `an empty collection is a zero-count no-op that starts nothing`() = runDbTest {
        newProvider(id = 1)
        val playlist = seedPlaylist(providerId = 1).let {
            playlistDao.upsert(it.copy(entryCount = 0))
            it
        }
        val actions = actions()

        val result = actions.enqueuePlaylist(playlist.id)

        assertThat(result).isEqualTo(LibraryDownloadResult())
        assertThat(requests).isEmpty()
        assertThat(starts).isEqualTo(0)
    }

    @Test
    fun `album rule toggles add then remove and wake the runtime only on a real change`() = runDbTest {
        newProvider(id = 1)
        val album = newAlbum(1, 1)
        val actions = actions()

        assertThat(actions.toggleAlbumRule(1, album.externalId!!)).isEqualTo(LibraryRuleToggle.ADDED)
        assertThat(actions.toggleAlbumRule(1, album.externalId)).isEqualTo(LibraryRuleToggle.REMOVED)
        assertThat(starts).isEqualTo(2)

        assertThat(actions.toggleAlbumRule(1, " ")).isEqualTo(LibraryRuleToggle.INVALID)
        assertThat(starts).isEqualTo(2)
        assertThat(db.downloadRuleDao().selectAll(ProfileScope.Unrestricted)).isEmpty()
    }
}
