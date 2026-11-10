package app.omnisonic.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.detail.DetailUiState
import app.omnisonic.feature.library.ui.AlbumDetailScreen
import app.omnisonic.feature.library.ui.ArtistDetailScreen
import app.omnisonic.feature.library.ui.PlaylistDetailScreen
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the detail screens' content branches (the shell route test only
 * reaches the Missing branch through the empty doubles): header actions,
 * track/entry rows, the artist albums strip, and the missing-state back
 * affordance. Behavior lives in `:feature-library` view-model tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DetailScreenRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun songs(count: Int) = (1..count).map { index ->
        SongEntity(
            id = index.toLong(),
            providerId = 1,
            externalId = "e-$index",
            title = "Song $index",
            displayArtist = "Artist",
            durationMs = 200_000L,
            updatedAt = 0,
        )
    }

    @Test
    fun albumContentRendersHeaderActionsAndTrackRows() {
        composeRule.setContent {
            OmnisonicTheme {
                AlbumDetailScreen(
                    state = DetailUiState.Album(
                        header = AlbumEntity(
                            id = 10,
                            providerId = 1,
                            externalId = "alb-10",
                            title = "Night Ride",
                            displayArtist = "Artist",
                            year = 1998,
                            songCount = 2,
                            updatedAt = 0,
                        ),
                        tracks = BrowseUiState.Content(songs(2), totalCount = 2, hasMore = false),
                    ),
                    onPlayAll = {},
                    onPlayFrom = {},
                    onShuffle = {},
                    onLoadMore = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Night Ride").assertExists()
        composeRule.onNodeWithText("1998 · 2 songs").assertExists()
        composeRule.onNodeWithText("Play").assertExists()
        composeRule.onNodeWithText("Shuffle").assertExists()
        composeRule.onNodeWithText("Song 1").assertExists()
        composeRule.onNodeWithText("Song 2").assertExists()
        composeRule.onAllNodesWithText("3:20").onFirst().assertExists()
    }

    @Test
    fun trackTapReportsTheTappedRowIndex() {
        val tapped = mutableListOf<Int>()
        composeRule.setContent {
            OmnisonicTheme {
                AlbumDetailScreen(
                    state = DetailUiState.Album(
                        header = AlbumEntity(id = 10, providerId = 1, title = "Night Ride", songCount = 2, updatedAt = 0),
                        tracks = BrowseUiState.Content(songs(2), totalCount = 2, hasMore = false),
                    ),
                    onPlayAll = {},
                    onPlayFrom = { tapped.add(it) },
                    onShuffle = {},
                    onLoadMore = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Song 2").performClick()

        assertThat(tapped).containsExactly(1)
    }

    @Test
    fun artistContentRendersTopTracksAndAlbumsStrip() {
        composeRule.setContent {
            OmnisonicTheme {
                ArtistDetailScreen(
                    state = DetailUiState.Artist(
                        header = ArtistEntity(
                            id = 20,
                            providerId = 1,
                            externalId = "art-20",
                            title = "Artist",
                            songCount = 2,
                            albumCount = 2,
                            updatedAt = 0,
                        ),
                        tracks = BrowseUiState.Content(songs(2), totalCount = 2, hasMore = false),
                        albums = BrowseUiState.Content(
                            listOf(
                                AlbumEntity(id = 1, providerId = 1, externalId = "alb-1", title = "First", updatedAt = 0),
                                AlbumEntity(id = 2, providerId = 1, externalId = "alb-2", title = "Second", updatedAt = 0),
                            ),
                            totalCount = 2,
                            hasMore = false,
                        ),
                    ),
                    onPlayAll = {},
                    onPlayFrom = {},
                    onShuffle = {},
                    onLoadMoreTracks = {},
                    onLoadMoreAlbums = {},
                    onRetryTracks = {},
                    onRetryAlbums = {},
                    onOpenAlbum = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Top tracks").assertExists()
        composeRule.onNodeWithText("Albums").assertExists()
        composeRule.onNodeWithText("Song 1").assertExists()
        composeRule.onNodeWithText("First").assertExists()
        composeRule.onNodeWithText("Second").assertExists()
    }

    @Test
    fun albumStripTileOpensTheAlbum() {
        val opened = mutableListOf<Long>()
        composeRule.setContent {
            OmnisonicTheme {
                ArtistDetailScreen(
                    state = DetailUiState.Artist(
                        header = ArtistEntity(id = 20, providerId = 1, title = "Artist", updatedAt = 0),
                        tracks = BrowseUiState.Empty,
                        albums = BrowseUiState.Content(
                            listOf(AlbumEntity(id = 7, providerId = 1, title = "First", updatedAt = 0)),
                            totalCount = 1,
                            hasMore = false,
                        ),
                    ),
                    onPlayAll = {},
                    onPlayFrom = {},
                    onShuffle = {},
                    onLoadMoreTracks = {},
                    onLoadMoreAlbums = {},
                    onRetryTracks = {},
                    onRetryAlbums = {},
                    onOpenAlbum = { opened.add(it) },
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("First").performClick()

        assertThat(opened).containsExactly(7L)
    }

    @Test
    fun playlistContentRendersEntriesInPositionOrder() {
        composeRule.setContent {
            OmnisonicTheme {
                PlaylistDetailScreen(
                    state = DetailUiState.Playlist(
                        header = PlaylistEntity(
                            id = 30,
                            providerId = 1,
                            externalId = "pl-30",
                            title = "Road mix",
                            type = 0,
                            entryCount = 2,
                            updatedAt = 0,
                        ),
                        entries = BrowseUiState.Content(
                            (1..2).map { index ->
                                PlaylistEntryEntity(
                                    id = index.toLong(),
                                    playlistId = 30,
                                    providerId = 1,
                                    position = index - 1,
                                    externalId = "e-$index",
                                    title = "Entry $index",
                                    addedAt = 0,
                                )
                            },
                            totalCount = 2,
                            hasMore = false,
                        ),
                    ),
                    onPlayAll = {},
                    onPlayFrom = {},
                    onShuffle = {},
                    onLoadMore = {},
                    onRetry = {},
                    onRetryEntries = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Road mix").assertExists()
        composeRule.onNodeWithText("2 tracks").assertExists()
        composeRule.onNodeWithText("Entry 1").assertExists()
        composeRule.onNodeWithText("Entry 2").assertExists()
    }

    @Test
    fun missingStateOffersTheWayBack() {
        val backed = mutableListOf<Unit>()
        composeRule.setContent {
            OmnisonicTheme {
                AlbumDetailScreen(
                    state = DetailUiState.Missing,
                    onPlayAll = {},
                    onPlayFrom = {},
                    onShuffle = {},
                    onLoadMore = {},
                    onRetry = {},
                    onBack = { backed.add(Unit) },
                )
            }
        }

        composeRule.onNodeWithText("Gone from the server").assertExists()
        composeRule.onNodeWithText("Go back").performClick()

        assertThat(backed).hasSize(1)
    }
}
