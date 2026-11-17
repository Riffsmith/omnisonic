package app.omnisonic.shell

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.search.RecentPick
import app.omnisonic.core.data.search.RecentPickKind
import app.omnisonic.core.data.search.SearchPage
import app.omnisonic.core.data.search.SearchResults
import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.feature.search.search.SearchPhase
import app.omnisonic.feature.search.search.SearchUiState
import app.omnisonic.feature.search.ui.SearchScreen
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.SearchPageConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SearchScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val song = SongEntity(
        id = 1,
        providerId = 1,
        externalId = "e-1",
        title = "Doomsday",
        displayArtist = "Artist",
        updatedAt = 0,
    )

    private val album = AlbumEntity(
        id = 2,
        providerId = 1,
        externalId = "album-2",
        title = "Doomsday Album",
        displayArtist = "Artist",
        updatedAt = 0,
    )

    private fun resultsState() = SearchUiState(
        query = "do",
        submittedQuery = "do",
        phase = SearchPhase.RESULTS,
        results = SearchResults(
            query = "do",
            terms = listOf("do"),
            categories = SearchCategory.entries.toSet(),
            songs = SearchPage(listOf(song), 0, 30, false),
            albums = SearchPage(listOf(album), 0, 30, false),
            artists = SearchPage.empty<ArtistEntity>(0, 30),
            genres = SearchPage.empty<GenreEntity>(0, 30),
            playlists = SearchPage.empty<PlaylistEntity>(0, 30),
        ),
    )

    @Test
    fun songRowTapDispatchesPlayAndAlbumRowStillOpensTheAlbum() {
        val played = mutableListOf<Long>()
        val openedAlbums = mutableListOf<Long>()
        composeRule.setContent {
            OmnisonicTheme {
                SearchScreen(
                    state = resultsState(),
                    config = SearchPageConfig.DEFAULT,
                    isActive = false,
                    onQueryChange = {},
                    onSubmit = {},
                    onClearQuery = {},
                    onToggleCategory = {},
                    onRecentPick = {},
                    onRemoveRecentPick = { _, _ -> },
                    onClearRecentPicks = {},
                    onRetry = {},
                    onLoadMore = {},
                    onPlaySong = { played += it },
                    onOpenAlbum = { openedAlbums += it },
                    onOpenArtist = {},
                    onOpenGenre = {},
                    onOpenPlaylist = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Doomsday").assertHasClickAction().performClick()
        composeRule.waitForIdle()
        assertThat(played).containsExactly(1L)
        assertThat(openedAlbums).isEmpty()

        composeRule.onNodeWithText("Doomsday Album").assertHasClickAction().performClick()
        composeRule.waitForIdle()
        assertThat(openedAlbums).containsExactly(2L)
    }

    @Test
    fun recentsRenderArtworkAndTypeAndRemoveByIdentity() {
        val removed = mutableListOf<Pair<RecentPickKind, Long>>()
        val state = SearchUiState(
            phase = SearchPhase.IDLE,
            recentPicks = listOf(
                RecentPick(
                    kind = RecentPickKind.SONG,
                    id = 1,
                    providerId = 1,
                    title = "Doomsday",
                    subtitle = "Artist",
                    imagePath = "doomsday.jpg",
                ),
                RecentPick(
                    kind = RecentPickKind.ALBUM,
                    id = 2,
                    providerId = 1,
                    title = "Doomsday Album",
                ),
            ),
        )
        composeRule.setContent {
            OmnisonicTheme {
                SearchScreen(
                    state = state,
                    config = SearchPageConfig.DEFAULT,
                    isActive = false,
                    onQueryChange = {},
                    onSubmit = {},
                    onClearQuery = {},
                    onToggleCategory = {},
                    onRecentPick = {},
                    onRemoveRecentPick = { kind, id -> removed += kind to id },
                    onClearRecentPicks = {},
                    onRetry = {},
                    onLoadMore = {},
                    onPlaySong = {},
                    onOpenAlbum = {},
                    onOpenArtist = {},
                    onOpenGenre = {},
                    onOpenPlaylist = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Recent searches").assertExists()
        composeRule.onNodeWithText("Doomsday").assertExists()
        composeRule.onNodeWithText("Songs · Artist").assertExists()
        composeRule.onNodeWithText("Doomsday Album").assertExists()

        composeRule.onNodeWithContentDescription("Remove Doomsday").performClick()
        composeRule.waitForIdle()
        assertThat(removed).containsExactly(RecentPickKind.SONG to 1L)
    }
}

