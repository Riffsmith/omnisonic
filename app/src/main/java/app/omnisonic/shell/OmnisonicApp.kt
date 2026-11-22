package app.omnisonic.shell

import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.omnisonic.feature.library.browse.LibraryBrowseViewModel
import app.omnisonic.feature.library.browse.LibrarySection
import app.omnisonic.feature.library.detail.DetailViewModel
import app.omnisonic.feature.library.history.HistoryViewModel
import app.omnisonic.feature.library.miniplayer.MiniPlayerUiState
import app.omnisonic.feature.library.miniplayer.MiniPlayerViewModel
import app.omnisonic.feature.library.nowplaying.NowPlayingViewModel
import app.omnisonic.feature.downloads.workspace.DownloadsRoute
import app.omnisonic.feature.downloads.workspace.DownloadsViewModel
import app.omnisonic.feature.library.downloads.LibraryDownloadActionsViewModel
import app.omnisonic.feature.library.queue.QueueViewModel
import app.omnisonic.feature.library.ui.AlbumDetailRoute
import app.omnisonic.feature.library.ui.ArtistDetailRoute
import app.omnisonic.feature.library.ui.GenreDetailRoute
import app.omnisonic.feature.library.ui.HistoryRoute
import app.omnisonic.feature.library.ui.LibrarySectionRoute
import app.omnisonic.feature.library.ui.MiniPlayerBar
import app.omnisonic.feature.library.ui.NowPlayingRoute
import app.omnisonic.feature.library.ui.PlayerSheetDrag
import app.omnisonic.feature.library.ui.PlaylistDetailRoute
import app.omnisonic.feature.library.ui.QueueRoute
import app.omnisonic.feature.search.search.SearchViewModel
import app.omnisonic.feature.settings.downloads.DownloadSettingsViewModel
import app.omnisonic.feature.settings.history.HistorySettingsViewModel
import app.omnisonic.feature.settings.providers.ProviderSetupRoute
import app.omnisonic.feature.settings.providers.ProviderSetupViewModel
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellNavigationResolver
import app.omnisonic.feature.settings.sync.LibrarySyncManagerViewModel
import app.omnisonic.feature.settings.sync.LibrarySyncStepRoute
import app.omnisonic.feature.settings.sync.LibrarySyncStepViewModel
import app.omnisonic.feature.settings.sync.PendingSyncViewModel
import app.omnisonic.feature.settings.transcode.TranscodeSettingsViewModel
import app.omnisonic.feature.settings.ui.ShellSettingsScreen
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.ui.label
import app.omnisonic.style.NavigationLabelMode
import app.omnisonic.style.NavigationLayout
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.TabSwipeMode
import app.omnisonic.style.rememberSwipeZoneRegistry
import app.omnisonic.style.toEasing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun OmnisonicApp(
    viewModel: ShellViewModel = hiltViewModel(),
    providerSetupViewModel: ProviderSetupViewModel = hiltViewModel(),
    syncManagerViewModel: LibrarySyncManagerViewModel = hiltViewModel(),
    pendingSyncViewModel: PendingSyncViewModel = hiltViewModel(),
    miniPlayerViewModel: MiniPlayerViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
    openDownloadsRequest: Boolean = false,
    onOpenDownloadsHandled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val miniPlayer by miniPlayerViewModel.state.collectAsStateWithLifecycle()
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
                historySettingsViewModel = hiltViewModel(),
                providerSetupViewModel = providerSetupViewModel,
                browseViewModel = hiltViewModel(),
                syncManagerViewModel = syncManagerViewModel,
                pendingSyncViewModel = pendingSyncViewModel,
                searchViewModel = searchViewModel,
                miniPlayerState = miniPlayer,
                onMiniPlayerTogglePlayPause = {
                    viewModel.logUserAction("mini-player:toggle")
                    miniPlayerViewModel.togglePlayPause()
                },
                onMiniPlayerPrevious = {
                    viewModel.logUserAction("mini-player:previous")
                    miniPlayerViewModel.previous()
                },
                onMiniPlayerNext = {
                    viewModel.logUserAction("mini-player:next")
                    miniPlayerViewModel.next()
                },
                libraryDownloadViewModelProvider = { hiltViewModel() },
                downloadSettingsViewModelProvider = { hiltViewModel() },
                transcodeSettingsViewModelProvider = { hiltViewModel() },
                onRouteChanged = viewModel::logNavigation,
                onUserAction = viewModel::logUserAction,
                openDownloadsRequest = openDownloadsRequest,
                onOpenDownloadsHandled = onOpenDownloadsHandled,
            )
        }
    }
}

/**
 * The pager page index is the selected main tab (GESTURES.md §4.2); the
 * NavHost keeps the §5 route table resolvable and hosts the non-tab
 * destinations. Tab taps animate the pager and move the back stack, a settled
 * swipe follows the route, and route arrivals (deep links, back pops) snap the
 * idle pager. [navController] and [sheetState] are test seams; production code
 * lets the shell remember its own.
 */
@Composable
fun OmnisonicShell(
    state: ShellUiState,
    settingsViewModel: ShellSettingsViewModel,
    historySettingsViewModel: HistorySettingsViewModel,
    providerSetupViewModel: ProviderSetupViewModel,
    browseViewModel: LibraryBrowseViewModel,
    syncManagerViewModel: LibrarySyncManagerViewModel,
    pendingSyncViewModel: PendingSyncViewModel,
    searchViewModel: SearchViewModel,
    miniPlayerState: MiniPlayerUiState = MiniPlayerUiState(),
    onMiniPlayerTogglePlayPause: () -> Unit = {},
    onMiniPlayerPrevious: () -> Unit = {},
    onMiniPlayerNext: () -> Unit = {},
    navController: NavHostController? = null,
    sheetState: PlayerSheetState? = null,
    syncStepViewModelProvider: @Composable () -> LibrarySyncStepViewModel = { hiltViewModel() },
    detailViewModelProvider: @Composable () -> DetailViewModel = { hiltViewModel() },
    nowPlayingViewModelProvider: @Composable () -> NowPlayingViewModel = { hiltViewModel() },
    queueViewModelProvider: @Composable () -> QueueViewModel = { hiltViewModel() },
    historyViewModelProvider: @Composable () -> HistoryViewModel = { hiltViewModel() },
    downloadsViewModelProvider: @Composable () -> DownloadsViewModel = { hiltViewModel() },
    libraryDownloadViewModelProvider: @Composable () -> LibraryDownloadActionsViewModel? = { null },
    downloadSettingsViewModelProvider: @Composable () -> DownloadSettingsViewModel? = { null },
    transcodeSettingsViewModelProvider: @Composable () -> TranscodeSettingsViewModel? = { null },
    onRouteChanged: (String) -> Unit = {},
    onUserAction: (String) -> Unit = {},
    openDownloadsRequest: Boolean = false,
    onOpenDownloadsHandled: () -> Unit = {},
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
        onUserAction("tab:${tab.id}")
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

    // Notification content intent: cold and warm launches arrive as the same
    // request, so one effect navigates the extend-only downloads route and
    // hands back consumption. Back then behaves like any pushed destination.
    LaunchedEffect(openDownloadsRequest) {
        if (openDownloadsRequest) {
            controller.navigate(ShellRoutes.DOWNLOADS) { launchSingleTop = true }
            onOpenDownloadsHandled()
        }
    }

    val swipeZoneRegistry = rememberSwipeZoneRegistry()
    val currentRoute = backStackEntry?.destination?.route ?: state.startRoute
    LaunchedEffect(currentRoute) { onRouteChanged(currentRoute) }
    val showMiniPlayer = miniPlayerState.isVisible && currentRoute != ShellRoutes.NOW_PLAYING

    // The player is a shell-level sheet (ui/player-sheet.md §1): it slides with
    // the finger over the current route instead of navigating to it, so the
    // route underneath stays composed and is revealed by a downward drag.
    val sheet = sheetState ?: rememberPlayerSheetState()
    val context = LocalContext.current
    // One fling gate for every sheet release (PlayerSheetRules.settleTarget and
    // pullTarget): the device's own minimum fling velocity, so a detent decision
    // does not depend on a fraction of a particular screen height.
    val flingVelocityPx = remember(context) {
        android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    }
    val sheetDrag = remember(sheet, flingVelocityPx) {
        PlayerSheetDrag(
            onDrag = { dy -> sheet.drag(dy) },
            onRelease = { velocityY -> sheet.release(velocityY, flingVelocityPx) },
        )
    }
    val pullCommitPx = with(LocalDensity.current) {
        tokens.layout.sheetPullCommitDistance.toPx()
    }
    val swipeUpTargetDrag = remember(sheet, flingVelocityPx, pullCommitPx) {
        PlayerSheetDrag(
            onDrag = { dy -> sheet.dragFromSwipeUpTarget(dy) },
            onRelease = { velocityY ->
                sheet.releaseFromSwipeUpTarget(velocityY, flingVelocityPx, pullCommitPx)
            },
        )
    }
    // Read through derived state so the target recomposes on the reveal edge,
    // not on every drag frame.
    val swipeUpTargetRevealed by remember(sheet) {
        derivedStateOf { sheet.swipeUpTargetRevealed }
    }
    // The sheet owns back while it is visible (D-34). Navigation's own back
    // handling is registered by the NavHost and is enabled whenever the back
    // stack is deeper than its root, so it wins the dispatcher's newest-first
    // dispatch and pops the hidden route instead of closing the sheet.
    // `NavHostController.enableOnBackPressed` does not reach that handler on
    // navigation-compose 2.8.2 (the NavHost registers its own predictive-back
    // callback), so the sheet's callback is added at the visibility edge —
    // after anything the shell composed before it — and removed again at peek.
    val backDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetVisible = sheet.isVisible
    DisposableEffect(backDispatcherOwner, lifecycleOwner, sheet, sheetVisible) {
        val dispatcher = backDispatcherOwner?.onBackPressedDispatcher
        if (!sheetVisible || dispatcher == null) return@DisposableEffect onDispose {}
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = sheet.onBack()
        }
        dispatcher.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }

    val nowPlayingSheet: @Composable () -> Unit = {
        NowPlayingRoute(
            viewModel = nowPlayingViewModelProvider(),
            onCollapse = {
                onUserAction("player:collapse")
                sheet.close()
            },
            onOpenQueue = {
                onUserAction("player:open-queue")
                sheet.openSwipeUpTarget()
            },
            onOpenLibrary = {
                onUserAction("player:open-library")
                sheet.close()
                controller.navigateTo(ShellRoutes.LIBRARY)
            },
            sheetDrag = sheetDrag,
        )
    }
    // The swipe-up target is a slot so a later phase can make it configurable
    // (ui/player-sheet.md §4); the queue is only the default. A slot that owns
    // scrollable content opts into the return gesture with the bounded drag.
    val swipeUpTargetSheet: @Composable () -> Unit = {
        QueueRoute(
            viewModel = queueViewModelProvider(),
            onBack = {
                onUserAction("queue:back-to-player")
                sheet.openNowPlaying()
            },
            onOpenLibrary = {
                onUserAction("queue:open-library")
                sheet.close()
                controller.navigateTo(ShellRoutes.LIBRARY)
            },
            sheetDrag = swipeUpTargetDrag,
            isRevealed = swipeUpTargetRevealed,
        )
    }

    val miniPlayer: @Composable (Modifier) -> Unit = { miniModifier ->
        if (showMiniPlayer) {
            MiniPlayerBar(
                state = miniPlayerState,
                swipeZoneRegistry = swipeZoneRegistry,
                onTogglePlayPause = onMiniPlayerTogglePlayPause,
                onPrevious = onMiniPlayerPrevious,
                onNext = onMiniPlayerNext,
                onExpand = {
                    onUserAction("mini-player:expand")
                    sheet.openNowPlaying()
                },
                modifier = miniModifier,
                sheetDrag = sheetDrag,
                sheetPosition = { sheet.position },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { sheet.viewportPx = it.height.toFloat() }
            // Gesture ownership for the sheet (D-25): the shell root sees every
            // pointer stream, including the ones that start on the mini bar while
            // the sheet is still off-screen. It never consumes, so child drag
            // detection is untouched.
            .pointerInput(sheet, flingVelocityPx, pullCommitPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.pressed }) {
                            sheet.onPointerDown()
                        } else {
                            sheet.onAllPointersUp(flingVelocityPx, pullCommitPx)
                        }
                    }
                }
            },
    ) {
        if (isLandscape && navLayout == NavigationLayout.RAIL) {
            // Insets are applied once, here: content, mini-player and rail move
            // above the IME together (search-tab-fixes.md §B2). Navigation bars
            // are handled too because targetSdk 36 is edge-to-edge.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                ShellNavRail(
                    tabs = state.tabs,
                    pagerState = pagerState,
                    labelMode = labelMode,
                    onSelect = onTabTapped,
                )
                Column(modifier = Modifier.weight(1f)) {
                    ShellContentArea(
                        state = state,
                        pagerState = pagerState,
                        swipeEnabled = swipeEnabled,
                        showingTabs = showingTabs,
                        navController = controller,
                        settingsViewModel = settingsViewModel,
                        historySettingsViewModel = historySettingsViewModel,
                        providerSetupViewModel = providerSetupViewModel,
                        browseViewModel = browseViewModel,
                        syncStepViewModelProvider = syncStepViewModelProvider,
                        syncManagerViewModel = syncManagerViewModel,
                        pendingSyncViewModel = pendingSyncViewModel,
                        searchViewModel = searchViewModel,
                        detailViewModelProvider = detailViewModelProvider,
                        nowPlayingViewModelProvider = nowPlayingViewModelProvider,
                        queueViewModelProvider = queueViewModelProvider,
                        historyViewModelProvider = historyViewModelProvider,
                        downloadsViewModelProvider = downloadsViewModelProvider,
                        libraryDownloadViewModelProvider = libraryDownloadViewModelProvider,
                        downloadSettingsViewModelProvider = downloadSettingsViewModelProvider,
                        transcodeSettingsViewModelProvider = transcodeSettingsViewModelProvider,
                        onOpenDownloads = { controller.navigateTo(ShellRoutes.DOWNLOADS) },
                        onOpenSetup = { controller.navigateTo(ShellRoutes.PROVIDER_SETUP) },
                        onTabTapped = onTabTapped,
                        onOpenSection = { controller.navigateTo(ShellRoutes.section(it.id)) },
                        onOpenHistory = { controller.navigateTo(ShellRoutes.HISTORY) },
                        onOpenAlbum = { controller.navigateTo(ShellRoutes.album(it)) },
                        onOpenArtist = { controller.navigateTo(ShellRoutes.artist(it)) },
                        onOpenGenre = { controller.navigateTo(ShellRoutes.genre(it)) },
                        onOpenPlaylist = { controller.navigateTo(ShellRoutes.playlist(it)) },
                        modifier = Modifier.weight(1f),
                    )
                    miniPlayer(Modifier)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                ShellContentArea(
                    state = state,
                    pagerState = pagerState,
                    swipeEnabled = swipeEnabled,
                    showingTabs = showingTabs,
                    navController = controller,
                    settingsViewModel = settingsViewModel,
                    historySettingsViewModel = historySettingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                    browseViewModel = browseViewModel,
                    syncStepViewModelProvider = syncStepViewModelProvider,
                    syncManagerViewModel = syncManagerViewModel,
                    pendingSyncViewModel = pendingSyncViewModel,
                    searchViewModel = searchViewModel,
                    detailViewModelProvider = detailViewModelProvider,
                    nowPlayingViewModelProvider = nowPlayingViewModelProvider,
                    queueViewModelProvider = queueViewModelProvider,
                    historyViewModelProvider = historyViewModelProvider,
                    downloadsViewModelProvider = downloadsViewModelProvider,
                    libraryDownloadViewModelProvider = libraryDownloadViewModelProvider,
                    downloadSettingsViewModelProvider = downloadSettingsViewModelProvider,
                    transcodeSettingsViewModelProvider = transcodeSettingsViewModelProvider,
                    onOpenDownloads = { controller.navigateTo(ShellRoutes.DOWNLOADS) },
                    onOpenSetup = { controller.navigateTo(ShellRoutes.PROVIDER_SETUP) },
                    onTabTapped = onTabTapped,
                    onOpenSection = { controller.navigateTo(ShellRoutes.section(it.id)) },
                    onOpenHistory = { controller.navigateTo(ShellRoutes.HISTORY) },
                    onOpenAlbum = { controller.navigateTo(ShellRoutes.album(it)) },
                    onOpenArtist = { controller.navigateTo(ShellRoutes.artist(it)) },
                    onOpenGenre = { controller.navigateTo(ShellRoutes.genre(it)) },
                    onOpenPlaylist = { controller.navigateTo(ShellRoutes.playlist(it)) },
                    modifier = Modifier.weight(1f),
                )
                miniPlayer(Modifier)
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
        PlayerSheetOverlay(
            state = sheet,
            swipeUpTarget = swipeUpTargetSheet,
            nowPlaying = nowPlayingSheet,
        )
    }
}

/**
 * Tab screens live in the pager; the NavHost hosts everything else. The two
 * layers swap z-order instead of composition so both stay alive: while a
 * non-tab route is on top the NavHost draws an opaque background (hiding the
 * pager) and its [inputBarrier] keeps the hidden pager out of the hit path.
 * The barrier never consumes: a Main-pass consumption on this ancestor
 * poisons every child scrollable's Final-pass consumption re-check and
 * cancels slow drag starts (`ui/gesture-contract.md`, task 4e bug record),
 * and the pager's own scrolling is disabled for the duration anyway.
 */
@Composable
private fun ShellContentArea(
    state: ShellUiState,
    pagerState: PagerState,
    swipeEnabled: Boolean,
    showingTabs: Boolean,
    navController: NavHostController,
    settingsViewModel: ShellSettingsViewModel,
    historySettingsViewModel: HistorySettingsViewModel,
    providerSetupViewModel: ProviderSetupViewModel,
    browseViewModel: LibraryBrowseViewModel,
    syncStepViewModelProvider: @Composable () -> LibrarySyncStepViewModel,
    syncManagerViewModel: LibrarySyncManagerViewModel,
    pendingSyncViewModel: PendingSyncViewModel,
    searchViewModel: SearchViewModel,
    detailViewModelProvider: @Composable () -> DetailViewModel,
    nowPlayingViewModelProvider: @Composable () -> NowPlayingViewModel,
    queueViewModelProvider: @Composable () -> QueueViewModel,
    historyViewModelProvider: @Composable () -> HistoryViewModel,
    downloadsViewModelProvider: @Composable () -> DownloadsViewModel,
    libraryDownloadViewModelProvider: @Composable () -> LibraryDownloadActionsViewModel?,
    downloadSettingsViewModelProvider: @Composable () -> DownloadSettingsViewModel?,
    transcodeSettingsViewModelProvider: @Composable () -> TranscodeSettingsViewModel? = { null },
    onOpenDownloads: () -> Unit,
    onOpenSetup: () -> Unit,
    onTabTapped: (MainTab) -> Unit,
    onOpenSection: (LibrarySection) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenGenre: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
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
            syncStepViewModelProvider = syncStepViewModelProvider,
            detailViewModelProvider = detailViewModelProvider,
            nowPlayingViewModelProvider = nowPlayingViewModelProvider,
            queueViewModelProvider = queueViewModelProvider,
            historyViewModelProvider = historyViewModelProvider,
            downloadsViewModelProvider = downloadsViewModelProvider,
            libraryDownloadViewModelProvider = libraryDownloadViewModelProvider,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (showingTabs) 0f else 1f)
                .background(if (showingTabs) Color.Transparent else MaterialTheme.colorScheme.background)
                .then(if (showingTabs) Modifier else Modifier.inputBarrier()),
        )
        MainTabsPager(
            tabs = state.tabs,
            state = pagerState,
            swipeEnabled = swipeEnabled && showingTabs,
            hasProvider = state.hasProvider,
            settingsViewModel = settingsViewModel,
            historySettingsViewModel = historySettingsViewModel,
            downloadSettingsViewModel = downloadSettingsViewModelProvider(),
            transcodeSettingsViewModel = transcodeSettingsViewModelProvider(),
            syncManagerViewModel = syncManagerViewModel,
            pendingSyncViewModel = pendingSyncViewModel,
            searchViewModel = searchViewModel,
            onOpenDownloads = onOpenDownloads,
            onOpenSetup = onOpenSetup,
            onSelectTab = onTabTapped,
            onOpenSection = onOpenSection,
            onOpenHistory = onOpenHistory,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            onOpenGenre = onOpenGenre,
            onOpenPlaylist = onOpenPlaylist,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (showingTabs) 1f else 0f),
        )
    }
}

/**
 * Hit-path barrier for the destination layer. The pointer-input node alone
 * keeps every touch inside the NavHost bounds from falling through to the
 * hidden pager layer behind it (a direct hit on the top sibling stops the
 * sibling hit test); the lambda stays empty because consuming here would
 * cancel child gesture detection — see [ShellContentArea].
 */
private fun Modifier.inputBarrier(): Modifier = pointerInput(Unit) { }

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
    detailViewModelProvider: @Composable () -> DetailViewModel = { hiltViewModel() },
    nowPlayingViewModelProvider: @Composable () -> NowPlayingViewModel = { hiltViewModel() },
    queueViewModelProvider: @Composable () -> QueueViewModel = { hiltViewModel() },
    historyViewModelProvider: @Composable () -> HistoryViewModel = { hiltViewModel() },
    downloadsViewModelProvider: @Composable () -> DownloadsViewModel = { hiltViewModel() },
    libraryDownloadViewModelProvider: @Composable () -> LibraryDownloadActionsViewModel? = { null },
    // Destination-scoped on purpose: the step resolves the provider row in init,
    // so it must be created when the route opens, never at app launch.
    syncStepViewModelProvider: @Composable () -> LibrarySyncStepViewModel = { hiltViewModel() },
    modifier: Modifier = Modifier,
) {
    // Player surfaces are bottom takeovers (D-19): slide up on push, down on
    // pop. `MotionTokens.durationScale` multiplies every duration (0 = snap).
    val routeMotion = OmnisonicTheme.tokens.motion
    val routeDurationMs =
        (routeMotion.nowPlayingRoute.durationMs * routeMotion.durationScale).roundToInt()
    val routeEasing = routeMotion.easingProfile.toEasing()
    val slideSpec = tween<IntOffset>(durationMillis = routeDurationMs, easing = routeEasing)
    val fadeSpec = tween<Float>(durationMillis = routeDurationMs, easing = routeEasing)

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
            val firstRun = navController.previousBackStackEntry == null
            ProviderSetupRoute(
                viewModel = providerSetupViewModel,
                onComplete = {
                    navController.navigate(
                        if (firstRun) ShellRoutes.LIBRARY_SYNC else ShellRoutes.HOME,
                    ) {
                        popUpTo(ShellRoutes.PROVIDER_SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onCancel = if (firstRun) {
                    null
                } else {
                    { navController.popBackStack() }
                },
            )
        }
        composable(ShellRoutes.LIBRARY_SYNC) {
            LibrarySyncStepRoute(
                viewModel = syncStepViewModelProvider(),
                onComplete = {
                    navController.navigate(ShellRoutes.HOME) {
                        popUpTo(ShellRoutes.LIBRARY_SYNC) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            ShellRoutes.ALBUM,
            arguments = listOf(navArgument(ShellRoutes.ARG_ALBUM_ID) { type = NavType.LongType }),
        ) { entry ->
            AlbumDetailRoute(
                viewModel = detailViewModelProvider(),
                albumId = entry.arguments?.getLong(ShellRoutes.ARG_ALBUM_ID) ?: 0L,
                onBack = { navController.popBackStack() },
                downloadViewModel = libraryDownloadViewModelProvider(),
            )
        }
        composable(
            ShellRoutes.ARTIST,
            arguments = listOf(navArgument(ShellRoutes.ARG_ARTIST_ID) { type = NavType.LongType }),
        ) { entry ->
            ArtistDetailRoute(
                viewModel = detailViewModelProvider(),
                artistId = entry.arguments?.getLong(ShellRoutes.ARG_ARTIST_ID) ?: 0L,
                onOpenAlbum = { id -> navController.navigateTo(ShellRoutes.album(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            ShellRoutes.GENRE,
            arguments = listOf(navArgument(ShellRoutes.ARG_GENRE_ID) { type = NavType.LongType }),
        ) { entry ->
            GenreDetailRoute(
                viewModel = detailViewModelProvider(),
                genreId = entry.arguments?.getLong(ShellRoutes.ARG_GENRE_ID) ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            ShellRoutes.PLAYLIST,
            arguments = listOf(navArgument(ShellRoutes.ARG_PLAYLIST_ID) { type = NavType.LongType }),
        ) { entry ->
            PlaylistDetailRoute(
                viewModel = detailViewModelProvider(),
                playlistId = entry.arguments?.getLong(ShellRoutes.ARG_PLAYLIST_ID) ?: 0L,
                onBack = { navController.popBackStack() },
                downloadViewModel = libraryDownloadViewModelProvider(),
            )
        }
        composable(
            ShellRoutes.NOW_PLAYING,
            enterTransition = { slideInVertically(slideSpec) { it } + fadeIn(fadeSpec) },
            popExitTransition = { slideOutVertically(slideSpec) { it } + fadeOut(fadeSpec) },
        ) {
            NowPlayingRoute(
                viewModel = nowPlayingViewModelProvider(),
                onCollapse = { navController.popBackStack() },
                onOpenQueue = { navController.navigateTo(ShellRoutes.QUEUE) },
                onOpenLibrary = { navController.navigateTo(ShellRoutes.LIBRARY) },
            )
        }
        composable(
            ShellRoutes.QUEUE,
            enterTransition = { slideInVertically(slideSpec) { it } + fadeIn(fadeSpec) },
            popExitTransition = { slideOutVertically(slideSpec) { it } + fadeOut(fadeSpec) },
        ) {
            QueueRoute(
                viewModel = queueViewModelProvider(),
                onBack = { navController.popBackStack() },
                onOpenLibrary = { navController.navigateTo(ShellRoutes.LIBRARY) },
            )
        }
        composable(ShellRoutes.HISTORY) {
            HistoryRoute(viewModel = historyViewModelProvider())
        }
        composable(ShellRoutes.DOWNLOADS) {
            DownloadsRoute(
                viewModel = downloadsViewModelProvider(),
                onBack = { navController.popBackStack() },
            )
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
