package app.omnisonic.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.browse.LibraryBrowseSource
import app.omnisonic.feature.library.browse.LibraryBrowseViewModel
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.style.DefaultStyleTokens
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.StyleTokens
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scroll-ownership regressions through the real pointer dispatch pipeline.
 *
 * Task 4d: `pagerEdgeExclusion` consumed every vertical drag on the Initial
 * pass, so only `SemanticsActions.ScrollBy`/`performScrollTo` (which bypass
 * the pointer pipeline) kept working — hence the pure-rule tests never caught
 * it. The `swipe()` helper below crosses touch slop on its first move event,
 * which is why the 4d tests stayed green while the device still swallowed
 * slow drag starts (see the Main-pass bug below).
 *
 * Task 4e: an ancestor consuming moves on the Main pass (the shell's
 * surplus-input absorber while a non-tab destination is on top) poisons the
 * Final-pass consumption re-check in `detectDragGestures`
 * (`awaitTouchSlopOrCancellation` re-reads the same event on Final and
 * cancels when anything consumed it). Slow drag starts — per-event deltas
 * under touch slop, the normal human touch — were cancelled before slop
 * could be crossed, while fast flicks and in-flight gestures (modifier
 * attached mid-gesture) still won. The slow-drag tests inject that real
 * event shape; `swipe()` cannot express it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LibraryScrollGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setShell(
        state: ShellUiState = fixedState(ShellRoutes.LIBRARY),
        tokens: StyleTokens = DefaultStyleTokens,
        browseViewModel: LibraryBrowseViewModel = testBrowseViewModel(),
    ): NavHostController {
        lateinit var nav: NavHostController
        val settingsViewModel = ShellSettingsViewModel(
            ShellPreferencesStore.createAt(File("/tmp/opencode", "scroll-${System.nanoTime()}.preferences_pb")),
        )
        val providerSetupViewModel = testProviderSetupViewModel()
        val stepViewModel = testLibrarySyncStepViewModel()
        composeRule.setContent {
            OmnisonicTheme(tokens = tokens) {
                OmnisonicShell(
                    state = state,
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                    browseViewModel = browseViewModel,
                    syncStepViewModelProvider = { stepViewModel },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    navController = rememberNavController().also { nav = it },
                )
            }
        }
        composeRule.waitForIdle()
        return nav
    }

    /** Scroll offsets of every vertical scrollable currently in the tree. */
    private fun verticalScrollOffsets(): List<Float> =
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.VerticalScrollAxisRange].value()
        }

    private fun dragUpOnContent() {
        composeRule.onRoot().performTouchInput {
            swipe(
                start = Offset(center.x, center.y),
                end = Offset(center.x, center.y - height * 0.45f),
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()
    }

    /**
     * A drag whose every move event stays under touch slop until the
     * cumulative distance crosses it — the event shape of a slow human drag
     * start. `swipe()` above splits into ~10 large steps, so it crosses slop
     * on the first move and can never reproduce the slow-start bug class.
     */
    private fun slowDragUpOnContent() {
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(0f, -3f)) }
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun verticalDragScrollsTheLibraryHub() {
        // One column makes the five hub entries taller than the viewport.
        setShell(
            tokens = DefaultStyleTokens.copy(
                layout = DefaultStyleTokens.layout.copy(gridColumnsPortrait = 1),
            ),
        )
        composeRule.waitForIdle()
        val before = verticalScrollOffsets()
        assertThat(before).isNotEmpty()
        assertThat(before.all { it == 0f }).isTrue()

        dragUpOnContent()

        assertThat(verticalScrollOffsets().any { it > 0f }).isTrue()
    }

    @Test
    fun verticalDragScrollsASectionGridUnderTheNavHostOverlay() {
        val nav = setShell(browseViewModel = LibraryBrowseViewModel(CountingBrowseSource(albumsPerPage = 60)))
        composeRule.onNodeWithText("Albums").performClick()
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.SECTION)
        composeRule.onNodeWithText("Album 1").assertExists()
        val before = verticalScrollOffsets()
        assertThat(before).isNotEmpty()
        assertThat(before.all { it == 0f }).isTrue()

        dragUpOnContent()

        assertThat(verticalScrollOffsets().any { it > 0f }).isTrue()
    }

    @Test
    fun slowVerticalDragStartsAScrollOnTheLibraryHub() {
        // Control for the section test below: on a tab page no ancestor
        // absorbs input, so a slow drag start must always scroll.
        setShell(
            tokens = DefaultStyleTokens.copy(
                layout = DefaultStyleTokens.layout.copy(gridColumnsPortrait = 1),
            ),
        )
        composeRule.waitForIdle()

        slowDragUpOnContent()

        assertThat(verticalScrollOffsets().any { it > 0f }).isTrue()
    }

    @Test
    fun slowVerticalDragStartsAScrollOnASectionScreen() {
        // The device bug (task 4e): the NavHost-layer input absorber consumed
        // moves on the Main pass, so the grid's slop detector saw its Final
        // re-check consumed and cancelled the drag before slop was crossed.
        val nav = setShell(browseViewModel = LibraryBrowseViewModel(CountingBrowseSource(albumsPerPage = 60)))
        composeRule.onNodeWithText("Albums").performClick()
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.SECTION)
        composeRule.onNodeWithText("Album 1").assertExists()
        val before = verticalScrollOffsets()
        assertThat(before).isNotEmpty()
        assertThat(before.all { it == 0f }).isTrue()

        slowDragUpOnContent()

        assertThat(verticalScrollOffsets().any { it > 0f }).isTrue()
    }

    @Test
    fun loadMoreFooterRequestsOnePagePerEntry() {
        // call 1 = initial page, call 2 = the footer's single auto-request,
        // call 3 = only reachable when the footer re-fires on append completion.
        val source = CountingBrowseSource(
            albumsPerPage = 1,
            hasMoreUntilCall = 3,
        )
        setShell(browseViewModel = LibraryBrowseViewModel(source))
        composeRule.onNodeWithText("Albums").performClick()
        composeRule.waitForIdle()

        assertThat(source.albumRequests).hasSize(2)
    }
}

/**
 * Fake browse surface for the gesture tests. [hasMoreUntilCall] lets a page
 * turn the footer off after a fixed number of requests, so a re-firing footer
 * cannot loop forever in the test.
 */
private class CountingBrowseSource(
    private val albumsPerPage: Int = 0,
    private val hasMoreUntilCall: Int = 0,
) : LibraryBrowseSource {

    override val libraryChanges: Flow<Unit> = emptyFlow()

    val albumRequests = mutableListOf<Pair<Int, Int>>()

    override suspend fun albums(offset: Int, limit: Int): BrowsePage<AlbumEntity> {
        albumRequests += offset to limit
        val items = (0 until albumsPerPage).map { index ->
            AlbumEntity(
                id = (offset + index + 1).toLong(),
                providerId = 1,
                title = "Album ${offset + index + 1}",
                updatedAt = 0,
            )
        }
        return BrowsePage(
            items = items,
            offset = offset,
            limit = limit,
            hasMore = albumRequests.size < hasMoreUntilCall,
        )
    }

    override suspend fun artists(offset: Int, limit: Int): BrowsePage<ArtistEntity> =
        BrowsePage(emptyList(), offset, limit, false)

    override suspend fun songs(offset: Int, limit: Int): BrowsePage<SongEntity> =
        BrowsePage(emptyList(), offset, limit, false)

    override suspend fun genres(offset: Int, limit: Int): BrowsePage<GenreEntity> =
        BrowsePage(emptyList(), offset, limit, false)

    override suspend fun playlists(offset: Int, limit: Int): BrowsePage<PlaylistEntity> =
        BrowsePage(emptyList(), offset, limit, false)

    override suspend fun albumCount(): Int = albumRequests.size

    override suspend fun artistCount(): Int = 0

    override suspend fun songCount(): Int = 0

    override suspend fun genreCount(): Int = 0

    override suspend fun playlistCount(): Int = 0
}

private fun fixedState(startRoute: String): ShellUiState = ShellUiState(
    tabs = MainTab.DEFAULT,
    startRoute = startRoute,
    onboardingComplete = true,
    hasProvider = true,
    portraitNavOverride = null,
    landscapeNavOverride = null,
)
