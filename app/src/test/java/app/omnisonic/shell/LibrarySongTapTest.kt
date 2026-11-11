package app.omnisonic.shell

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.browse.LibraryBrowseSource
import app.omnisonic.feature.library.browse.LibraryBrowseViewModel
import app.omnisonic.feature.library.browse.LibrarySection
import app.omnisonic.feature.library.ui.LibrarySectionRoute
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the device defect "tapping a song in Library → Songs does
 * nothing": the row carried no click action at all, so the VM's `playFrom`
 * was unreachable from the list. Asserts the row is tappable and that a tap
 * queues the loaded window at the tapped index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LibrarySongTapTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Records `play` calls; everything else is the shell no-op double. */
    private class RecordingController :
        PlaybackController by NoopPlaybackController() {
        val plays = mutableListOf<Pair<List<PlaybackQueueEntry>, Int>>()

        override suspend fun play(
            entries: List<PlaybackQueueEntry>,
            startEntriesIndex: Int,
            sourceType: String?,
            sourceData: String?,
        ) {
            plays += entries to startEntriesIndex
        }
    }

    private class SongBrowseSource(private val count: Int) : LibraryBrowseSource {
        override val libraryChanges: Flow<Unit> = emptyFlow()

        override suspend fun songs(offset: Int, limit: Int): BrowsePage<SongEntity> = BrowsePage(
            items = (offset until minOf(offset + limit, count)).map { index ->
                SongEntity(
                    id = (index + 1).toLong(),
                    providerId = 1,
                    externalId = "e-${index + 1}",
                    title = "Song ${index + 1}",
                    displayArtist = "Artist",
                    durationMs = 200_000L,
                    updatedAt = 0,
                )
            },
            offset = offset,
            limit = limit,
            hasMore = offset + limit < count,
        )

        override suspend fun albums(offset: Int, limit: Int) = BrowsePage<AlbumEntity>(emptyList(), offset, limit, false)
        override suspend fun artists(offset: Int, limit: Int) = BrowsePage<ArtistEntity>(emptyList(), offset, limit, false)
        override suspend fun genres(offset: Int, limit: Int) = BrowsePage<GenreEntity>(emptyList(), offset, limit, false)
        override suspend fun playlists(offset: Int, limit: Int) = BrowsePage<PlaylistEntity>(emptyList(), offset, limit, false)
        override suspend fun albumCount() = 0
        override suspend fun artistCount() = 0
        override suspend fun songCount() = count
        override suspend fun genreCount() = 0
        override suspend fun playlistCount() = 0
    }

    @Test
    fun songRowTapPlaysFromTheTappedRow() {
        val controller = RecordingController()
        val viewModel = LibraryBrowseViewModel(SongBrowseSource(count = 3), controller)
        composeRule.setContent {
            OmnisonicTheme {
                LibrarySectionRoute(section = LibrarySection.SONGS, viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Song 1").assertExists()
        composeRule.onNodeWithText("Song 2").assertHasClickAction().performClick()
        composeRule.waitForIdle()

        val (queued, start) = controller.plays.single()
        assertThat(start).isEqualTo(1)
        assertThat(queued.map { it.externalId }).containsExactly("e-1", "e-2", "e-3").inOrder()
    }
}
