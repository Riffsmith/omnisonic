package app.omnisonic.shell

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.omnisonic.BuildConfig
import app.omnisonic.R
import app.omnisonic.feature.library.browse.LibraryBrowseViewModel
import app.omnisonic.feature.library.browse.LibrarySection
import app.omnisonic.feature.library.ui.LibrarySectionRoute
import app.omnisonic.feature.settings.providers.ProviderSetupRoute
import app.omnisonic.feature.settings.providers.ProviderSetupViewModel
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellNavigationResolver
import app.omnisonic.feature.settings.ui.ShellSettingsScreen
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.ui.label
import app.omnisonic.style.NavigationLabelMode
import app.omnisonic.style.NavigationLayout
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.TabSwipeMode
import kotlinx.coroutines.launch

@Composable
fun OmnisonicApp(
    viewModel: ShellViewModel = hiltViewModel(),
    providerSetupViewModel: ProviderSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OmnisonicTheme {
        val shell = state
        if (shell == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {}
        } else {
            OmnisonicShell(
                state = shell,
                settingsViewModel = hiltViewModel(),
                providerSetupViewModel = providerSetupViewModel,
                browseViewModel = hiltViewModel(),
            )
        }
    }
}

/**
 * The pager page index is the selected main tab (GESTURES.md §4.2); the
 * NavHost keeps the §5 route table resolvable and hosts the non-tab
 * destinations. Tab taps animate the pager and move the back stack, a settled
 * swipe follows the route, and route arrivals (deep links, back pops) snap the
 * idle pager. [navController] is a test seam; production code lets the shell
 * remember its own.
 */
@Composable
fun OmnisonicShell(
    state: ShellUiState,
    settingsViewModel: ShellSettingsViewModel,
    providerSetupViewModel: ProviderSetupViewModel,
    browseViewModel: LibraryBrowseViewModel,
    navController: NavHostController? = null,
) {
    val controller = navController ?: rememberNavController()
    val backStackEntry by controller.currentBackStackEntryAsState()
    val tokens = OmnisonicTheme.tokens
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val navLayout = ShellNavigationResolver.resolve(
        tokens.layout,
        state.portraitNavOverride,
        state.landscapeNavOverride,
        isLandscape,
    )
    val labelMode = tokens.layout.labelModeFor(isLandscape)
    val swipeEnabled = tokens.layout.tabSwipeModeFor(isLandscape) == TabSwipeMode.ENABLED
    val tabRoutes = remember { MainTab.entries.map { it.id }.toSet() }
    val showingTabs = (backStackEntry?.destination?.route ?: state.startRoute) in tabRoutes

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = state.tabs.indexOfFirst { it.id == state.startRoute }.coerceAtLeast(0),
    ) {
        state.tabs.size
    }

    val onTabSelect: (String) -> Unit = { route ->
        controller.navigate(route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val onTabTapped: (MainTab) -> Unit = { tab ->
        val index = state.tabs.indexOf(tab)
        if (index >= 0) {
            scope.launch { pagerState.animateScrollToPage(index) }
        }
        onTabSelect(tab.id)
    }

    LaunchedEffect(pagerState, state.tabs) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val tab = state.tabs.getOrNull(page) ?: return@collect
            val route = backStackEntry?.destination?.route ?: state.startRoute
            if (route in tabRoutes && tab.id != route) {
                onTabSelect(tab.id)
            }
        }
    }
    LaunchedEffect(pagerState, state.tabs) {
        snapshotFlow { backStackEntry?.destination?.route }.collect { route ->
            val index = state.tabs.indexOfFirst { it.id == route }
            if (index >= 0 && !pagerState.isScrollInProgress && pagerState.currentPage != index) {
                pagerState.scrollToPage(index)
            }
        }
    }
    LaunchedEffect(state.tabs) {
        val route = backStackEntry?.destination?.route ?: state.startRoute
        if (route in tabRoutes && state.tabs.none { it.id == route }) {
            state.tabs.firstOrNull()?.let { onTabSelect(it.id) }
        }
    }

    if (isLandscape && navLayout == NavigationLayout.RAIL) {
        Row(modifier = Modifier.fillMaxSize()) {
            ShellNavRail(
                tabs = state.tabs,
                pagerState = pagerState,
                labelMode = labelMode,
                onSelect = onTabTapped,
            )
            ShellContentArea(
                state = state,
                pagerState = pagerState,
                swipeEnabled = swipeEnabled,
                showingTabs = showingTabs,
                navController = controller,
                settingsViewModel = settingsViewModel,
                providerSetupViewModel = providerSetupViewModel,
                browseViewModel = browseViewModel,
                onOpenSetup = { controller.navigateTo(ShellRoutes.PROVIDER_SETUP) },
                onTabTapped = onTabTapped,
                onOpenSection = { controller.navigateTo(ShellRoutes.section(it.id)) },
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            ShellContentArea(
                state = state,
                pagerState = pagerState,
                swipeEnabled = swipeEnabled,
                showingTabs = showingTabs,
                navController = controller,
                settingsViewModel = settingsViewModel,
                providerSetupViewModel = providerSetupViewModel,
                browseViewModel = browseViewModel,
                onOpenSetup = { controller.navigateTo(ShellRoutes.PROVIDER_SETUP) },
                onTabTapped = onTabTapped,
                onOpenSection = { controller.navigateTo(ShellRoutes.section(it.id)) },
                modifier = Modifier.weight(1f),
            )
            if (navLayout == NavigationLayout.BOTTOM_BAR) {
                ShellBottomBar(
                    tabs = state.tabs,
                    pagerState = pagerState,
                    labelMode = labelMode,
                    onSelect = onTabTapped,
                )
            }
        }
    }
}

/**
 * Tab screens live in the pager; the NavHost hosts everything else. The two
 * layers swap z-order instead of composition so both stay alive: while a
 * non-tab route is on top the NavHost draws an opaque background (hiding the
 * pager) and absorbs whatever input its content did not, so no tap or swipe
 * bleeds through to the hidden tab pages.
 */
@Composable
private fun ShellContentArea(
    state: ShellUiState,
    pagerState: PagerState,
    swipeEnabled: Boolean,
    showingTabs: Boolean,
    navController: NavHostController,
    settingsViewModel: ShellSettingsViewModel,
    providerSetupViewModel: ProviderSetupViewModel,
    browseViewModel: LibraryBrowseViewModel,
    onOpenSetup: () -> Unit,
    onTabTapped: (MainTab) -> Unit,
    onOpenSection: (LibrarySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        ShellNavHost(
            navController = navController,
            startRoute = state.startRoute,
            hasProvider = state.hasProvider,
            settingsViewModel = settingsViewModel,
            providerSetupViewModel = providerSetupViewModel,
            browseViewModel = browseViewModel,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (showingTabs) 0f else 1f)
                .background(if (showingTabs) Color.Transparent else MaterialTheme.colorScheme.background)
                .then(if (showingTabs) Modifier else Modifier.consumeSurplusInput()),
        )
        MainTabsPager(
            tabs = state.tabs,
            state = pagerState,
            swipeEnabled = swipeEnabled,
            hasProvider = state.hasProvider,
            settingsViewModel = settingsViewModel,
            onOpenSetup = onOpenSetup,
            onSelectTab = onTabTapped,
            onOpenSection = onOpenSection,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (showingTabs) 1f else 0f),
        )
    }
}

/**
 * Absorbs every gesture its content did not consume, in the main pass after
 * the destination content had its chance — keeps the hidden pager unreachable
 * while a non-tab destination is on top.
 */
private fun Modifier.consumeSurplusInput(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

@Composable
private fun ShellNavRail(
    tabs: List<MainTab>,
    pagerState: PagerState,
    labelMode: NavigationLabelMode,
    onSelect: (MainTab) -> Unit,
) {
    val navBarStyle = OmnisonicTheme.tokens.surfaces.navBar
    val selectedPage = pagerState.currentPage
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = navBarStyle.alpha),
    ) {
        tabs.forEachIndexed { index, tab ->
            NavigationRailItem(
                selected = index == selectedPage,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(), contentDescription = tab.label()) },
                label = navItemLabel(labelMode) { Text(tab.label()) },
                alwaysShowLabel = labelMode == NavigationLabelMode.ALL,
            )
        }
    }
}

@Composable
private fun ShellBottomBar(
    tabs: List<MainTab>,
    pagerState: PagerState,
    labelMode: NavigationLabelMode,
    onSelect: (MainTab) -> Unit,
) {
    val navBarStyle = OmnisonicTheme.tokens.surfaces.navBar
    val selectedPage = pagerState.currentPage
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = navBarStyle.alpha),
    ) {
        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = index == selectedPage,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(), contentDescription = tab.label()) },
                label = navItemLabel(labelMode) { Text(tab.label()) },
                alwaysShowLabel = labelMode == NavigationLabelMode.ALL,
            )
        }
    }
}

private fun navItemLabel(
    labelMode: NavigationLabelMode,
    label: @Composable () -> Unit,
): (@Composable () -> Unit)? = when (labelMode) {
    NavigationLabelMode.NONE -> null
    NavigationLabelMode.ALL, NavigationLabelMode.SELECTED_ONLY -> label
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.HOME -> Icons.Filled.Home
    MainTab.LIBRARY -> Icons.AutoMirrored.Filled.List
    MainTab.SEARCH -> Icons.Filled.Search
    MainTab.SETTINGS -> Icons.Filled.Settings
}

@Composable
internal fun ShellNavHost(
    navController: NavHostController,
    startRoute: String,
    hasProvider: Boolean,
    settingsViewModel: ShellSettingsViewModel,
    providerSetupViewModel: ProviderSetupViewModel,
    browseViewModel: LibraryBrowseViewModel,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable(ShellRoutes.HOME) {
            TabRouteSlot()
        }
        composable(ShellRoutes.LIBRARY) {
            TabRouteSlot()
        }
        composable(
            ShellRoutes.SECTION,
            arguments = listOf(navArgument(ShellRoutes.ARG_SECTION) { type = NavType.StringType }),
        ) { entry ->
            val section = LibrarySection.fromId(
                entry.arguments?.getString(ShellRoutes.ARG_SECTION).orEmpty(),
            )
            if (section == null) {
                PlaceholderPane(MainTab.LIBRARY.label())
            } else {
                LibrarySectionRoute(
                    section = section,
                    viewModel = browseViewModel,
                    onOpenAlbum = { id -> navController.navigateTo(ShellRoutes.album(id)) },
                    onOpenArtist = { id -> navController.navigateTo(ShellRoutes.artist(id)) },
                    onOpenGenre = { id -> navController.navigateTo(ShellRoutes.genre(id)) },
                    onOpenPlaylist = { id -> navController.navigateTo(ShellRoutes.playlist(id)) },
                )
            }
        }
        composable(ShellRoutes.SEARCH) {
            TabRouteSlot()
        }
        composable(ShellRoutes.SETTINGS) {
            TabRouteSlot()
        }
        composable(ShellRoutes.PROVIDER_SETUP) {
            ProviderSetupRoute(
                viewModel = providerSetupViewModel,
                onComplete = {
                    navController.navigate(ShellRoutes.HOME) {
                        popUpTo(ShellRoutes.PROVIDER_SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onCancel = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
        composable(
            ShellRoutes.ALBUM,
            arguments = listOf(navArgument(ShellRoutes.ARG_ALBUM_ID) { type = NavType.LongType }),
        ) {
            DetailPlaceholder()
        }
        composable(
            ShellRoutes.ARTIST,
            arguments = listOf(navArgument(ShellRoutes.ARG_ARTIST_ID) { type = NavType.LongType }),
        ) {
            DetailPlaceholder()
        }
        composable(
            ShellRoutes.GENRE,
            arguments = listOf(navArgument(ShellRoutes.ARG_GENRE_ID) { type = NavType.LongType }),
        ) {
            DetailPlaceholder()
        }
        composable(
            ShellRoutes.PLAYLIST,
            arguments = listOf(navArgument(ShellRoutes.ARG_PLAYLIST_ID) { type = NavType.LongType }),
        ) {
            DetailPlaceholder()
        }
        composable(ShellRoutes.NOW_PLAYING) {
            PlaceholderPane(stringResource(R.string.placeholder_screen_body))
        }
        composable(ShellRoutes.QUEUE) {
            PlaceholderPane(stringResource(R.string.placeholder_screen_body))
        }
    }
}

/**
 * Tab destinations render nothing: the route stays registered (§5 route
 * table, deep links, back stack) while the shell pager draws the tab screen
 * above the NavHost (task 2a pairing).
 */
@Composable
private fun TabRouteSlot() {
    Box(modifier = Modifier.fillMaxSize())
}

private fun NavHostController.navigateTo(route: String) {
    navigate(route) { launchSingleTop = true }
}
