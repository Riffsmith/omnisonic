package app.omnisonic.shell

import android.view.ViewConfiguration
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.omnisonic.BuildConfig
import app.omnisonic.R
import app.omnisonic.feature.library.browse.LibrarySection
import app.omnisonic.feature.library.ui.LibraryHubScreen
import app.omnisonic.feature.search.search.SearchViewModel
import app.omnisonic.feature.search.ui.SearchRoute
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.sync.LibrarySyncManagerViewModel
import app.omnisonic.feature.settings.ui.ShellSettingsScreen
import app.omnisonic.feature.settings.ui.label
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.gestureTwins
import app.omnisonic.style.pagerEdgeExclusion

/**
 * Tab+pager pairing (GESTURES.md §4.2): the hoisted [PagerState] is the single
 * source of truth for the selected main tab — tab taps animate it, swipes move
 * it, and the tab bar mirrors it. Swipes honor [TabSwipeMode] through
 * [swipeEnabled] and never claim drags inside the token edge-exclusion strip
 * (UI_SPEC.md §1.2). Tab swipe's button twin is the tab bar itself; the twins
 * below also install "go to tab" accessibility actions on the pager so
 * pointer-free users can switch tabs without swiping (UI_SPEC.md §1.3).
 */
@Composable
internal fun MainTabsPager(
    tabs: List<MainTab>,
    state: PagerState,
    swipeEnabled: Boolean,
    hasProvider: Boolean,
    settingsViewModel: ShellSettingsViewModel,
    syncManagerViewModel: LibrarySyncManagerViewModel,
    searchViewModel: SearchViewModel,
    onOpenSetup: () -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onOpenSection: (LibrarySection) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenGenre: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val edgeExclusionPx = with(LocalDensity.current) { tokens.layout.edgeExclusionDp.toPx() }
    val context = LocalContext.current
    val touchSlopPx = remember(context) { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    val twins = tabs.map { tab ->
        GestureTwin(
            gestureId = tab.id,
            actionLabel = stringResource(R.string.a11y_go_to_tab, tab.label()),
            twinControlLabel = tab.label(),
        )
    }
    HorizontalPager(
        state = state,
        modifier = modifier
            .pagerEdgeExclusion(edgeExclusionPx, touchSlopPx)
            .gestureTwins(twins) { gestureId -> MainTab.fromId(gestureId)?.let(onSelectTab) },
        userScrollEnabled = swipeEnabled,
    ) { page ->
        when (val tab = tabs[page]) {
            MainTab.HOME -> HomePlaceholder(hasProvider = hasProvider, onSetup = onOpenSetup)
            MainTab.LIBRARY -> LibraryHubScreen(onOpenSection = onOpenSection)
            MainTab.SEARCH -> SearchRoute(
                viewModel = searchViewModel,
                isActive = state.currentPage == page,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onOpenGenre = onOpenGenre,
                onOpenPlaylist = onOpenPlaylist,
            )
            MainTab.SETTINGS -> ShellSettingsScreen(
                appVersion = BuildConfig.VERSION_NAME,
                onOpenProviders = onOpenSetup,
                syncViewModel = syncManagerViewModel,
                viewModel = settingsViewModel,
            )
        }
    }
}
