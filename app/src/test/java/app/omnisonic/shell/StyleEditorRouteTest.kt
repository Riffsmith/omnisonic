package app.omnisonic.shell

import androidx.activity.OnBackPressedDispatcher
import androidx.test.core.app.ApplicationProvider
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.feature.library.nowplaying.NowPlayingViewModel
import app.omnisonic.feature.settings.R
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.OnboardingGate
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.styleeditor.StyleEditorHubScreen
import app.omnisonic.feature.settings.styleeditor.StyleEditorHubState
import app.omnisonic.feature.settings.styleeditor.StyleEditorViewModel
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun fixedShellState(
    startRoute: String = ShellRoutes.HOME,
    hasProvider: Boolean = true,
): ShellUiState = ShellUiState(
    tabs = MainTab.DEFAULT,
    startRoute = startRoute,
    onboardingComplete = startRoute == OnboardingGate.HOME_ROUTE,
    hasProvider = hasProvider,
    portraitNavOverride = null,
    landscapeNavOverride = null,
)

/** Bottom-bar items carry Role.Tab; the settings screen's rows do not. */
private fun tabItem(label: String): SemanticsMatcher = SemanticsMatcher("tab '$label'") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
}

private fun isAreaRow(): SemanticsMatcher = SemanticsMatcher("editor area row") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Open ") } == true
}

private fun areaRow(label: String): SemanticsMatcher = SemanticsMatcher("area row '$label'") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "Open $label" } == true
}

/** The Styles screen's single active-style preview card (D-116 Q9). */
private fun previewCard(): SemanticsMatcher = SemanticsMatcher("style preview card") { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription)
        ?.any { it.startsWith("Style preview: ") } == true
}

private fun duplicateAction(): SemanticsMatcher = SemanticsMatcher("duplicate action") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it.startsWith("Duplicate ") } == true
}

private fun renameAction(): SemanticsMatcher = SemanticsMatcher("rename action") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it.startsWith("Rename ") } == true
}

private fun deleteAction(): SemanticsMatcher = SemanticsMatcher("delete action") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it.startsWith("Delete ") } == true
}

private fun hasText(): SemanticsMatcher = SemanticsMatcher("has text") { node ->
    node.config.contains(SemanticsProperties.Text)
}

/** The rename dialog's one text field, whichever name it currently holds. */
private fun editableField(): SemanticsMatcher = SemanticsMatcher("editable field") { node ->
    node.config.contains(SemanticsProperties.EditableText)
}

/** The `{area}` argument of the current destination, for pattern-route assertions. */
private fun currentArea(nav: NavHostController): String? =
    nav.currentBackStackEntry?.arguments?.getString(ShellRoutes.ARG_AREA)

/**
 * TODO 5a on a real graph: the Settings row opens the hub, the hub lists the
 * seven areas in order as labelled buttons, the active name and preview follow
 * the store, back returns to Settings unchanged, the Home/Library previews use
 * the dedicated non-destructive routes, and the Now Playing preview opens the
 * hosted sheet over the editor route (D-100/D-109/D-114).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StyleEditorRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun appContext() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var sheet: PlayerSheetState
    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun setContent(
        editor: Pair<StyleEditorViewModel, app.omnisonic.core.data.style.StyleProfileStore> =
            testStyleEditorViewModel("route"),
        nowPlayingViewModel: NowPlayingViewModel = testNowPlayingViewModel(),
    ): NavHostController {
        lateinit var nav: NavHostController
        val settingsViewModel = ShellSettingsViewModel(
            ShellPreferencesStore.createAt(File("/tmp/opencode", "editor-${System.nanoTime()}.preferences_pb")),
        )
        val stepViewModel = testLibrarySyncStepViewModel()
        composeRule.setContent {
            OmnisonicTheme {
                val rememberedSheet = rememberPlayerSheetState()
                sheet = rememberedSheet
                backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
                    .onBackPressedDispatcher
                OmnisonicShell(
                    state = fixedShellState(),
                    settingsViewModel = settingsViewModel,
                    historySettingsViewModel = testHistorySettingsViewModel(),
                    providerSetupViewModel = testProviderSetupViewModel(),
                    browseViewModel = testBrowseViewModel(),
                    syncStepViewModelProvider = { stepViewModel },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    searchViewModel = testSearchViewModel(),
                    navController = rememberNavController().also { nav = it },
                    sheetState = rememberedSheet,
                    detailViewModelProvider = { testDetailViewModel() },
                    nowPlayingViewModelProvider = { nowPlayingViewModel },
                    queueViewModelProvider = { testQueueViewModel() },
                    historyViewModelProvider = { testHistoryViewModel() },
                    downloadsViewModelProvider = { testDownloadsViewModel() },
                    styleEditorViewModelProvider = { editor.first },
                )
            }
        }
        composeRule.waitForIdle()
        return nav
    }

    private fun openEditor() {
        composeRule.onNode(tabItem("Settings")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Style editor").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun settingsRowAppearsOnceAndNavigatesToTheEditorHub() {
        val nav = setContent()

        composeRule.onNode(tabItem("Settings")).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Style editor").assertCountEquals(1)

        composeRule.onNodeWithText("Style editor").performClick()
        composeRule.waitForIdle()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR)
        composeRule.onNode(areaRow("Styles")).assertExists()
    }

    @Test
    fun hubListsEveryAreaRowInOrderAsLabelledButtons() {
        val nav = setContent()
        openEditor()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR)

        val rows = composeRule.onAllNodes(isAreaRow()).fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)!!.first() }

        assertThat(rows).containsExactly(
            "Open Styles",
            "Open Appearance",
            "Open Home",
            "Open Library",
            "Open Now playing",
            "Open Import/export",
            "Open Advanced",
        ).inOrder()
    }

    @Test
    fun hubShowsTheActiveStyleNameAndLivePreviewFromTheStore() {
        val editor = testStyleEditorViewModel("hub")
        setContent(editor)
        openEditor()

        composeRule.onNodeWithContentDescription("Style preview: Default Style").assertExists()

        composeRule.runOnIdle { runBlocking { editor.second.switch("builtin-compact-list") } }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Style preview: Compact List").assertExists()
    }

    @Test
    fun backFromTheHubReturnsToSettingsWithoutAStyleChange() {
        val editor = testStyleEditorViewModel("back")
        val nav = setContent(editor)
        openEditor()

        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.SETTINGS)
        val activeId = runBlocking { withTimeout(5_000) { editor.second.activeId.first() } }
        assertThat(activeId).isNull()
    }

    @Test
    fun areaRowsOpenSubScreensAndTheBackChainReturnsToTheHub() {
        val nav = setContent()
        openEditor()

        composeRule.onNode(areaRow("Home")).performClick()
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_AREA)
        assertThat(currentArea(nav)).isEqualTo("home")
        composeRule.onNodeWithText("View on Home").assertExists()

        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR)

        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.SETTINGS)
    }

    @Test
    fun homePreviewUsesTheNonDestructiveRouteAndKeepsTheEditorSession() {
        val editor = testStyleEditorViewModel("homepreview")
        val nav = setContent(editor)
        composeRule.runOnIdle { runBlocking { editor.second.switch("builtin-compact-list") } }
        composeRule.waitForIdle()
        openEditor()

        composeRule.onNode(areaRow("Home")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("View on Home").performClick()
        composeRule.waitForIdle()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_PREVIEW)
        assertThat(currentArea(nav)).isEqualTo("home")
        assertThat(nav.currentBackStack.value.map { it.destination.route }.filterNotNull()).containsExactly(
            ShellRoutes.HOME,
            ShellRoutes.SETTINGS,
            ShellRoutes.STYLE_EDITOR,
            ShellRoutes.STYLE_EDITOR_AREA,
            ShellRoutes.STYLE_EDITOR_PREVIEW,
        ).inOrder()

        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR)
        composeRule.onNodeWithContentDescription("Style preview: Compact List").assertExists()
    }

    @Test
    fun libraryPreviewUsesTheNonDestructiveRouteAndKeepsTheEditorBackStack() {
        val nav = setContent()
        openEditor()

        composeRule.onNode(areaRow("Library")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("View on Library").performClick()
        composeRule.waitForIdle()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_PREVIEW)
        assertThat(currentArea(nav)).isEqualTo("library")
        assertThat(nav.previousBackStackEntry?.destination?.route)
            .isEqualTo(ShellRoutes.STYLE_EDITOR_AREA)
        assertThat(nav.currentBackStack.value.map { it.destination.route }).contains(ShellRoutes.STYLE_EDITOR)

        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_AREA)
        assertThat(currentArea(nav)).isEqualTo("library")
    }

    @Test
    fun nowPlayingPreviewOpensTheHostedSheetOverTheEditorRouteAndBackReturns() {
        val editor = testStyleEditorViewModel("nowplaying")
        val nowPlayingViewModel = testNowPlayingViewModel()
        val nav = setContent(editor, nowPlayingViewModel)
        openEditor()
        composeRule.runOnUiThread { sheet.viewportPx = 1_000f }

        composeRule.onNode(areaRow("Now playing")).performClick()
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_AREA)
        assertThat(currentArea(nav)).isEqualTo("nowPlaying")

        val playbackBefore = nowPlayingViewModel.state.value
        val undoBefore = editor.first.hubState.value.undoAvailable

        composeRule.onNodeWithText("View in Now playing").performClick()
        composeRule.waitForIdle()

        assertThat(sheet.isVisible).isTrue()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_AREA)
        assertThat(currentArea(nav)).isEqualTo("nowPlaying")
        assertThat(nowPlayingViewModel.state.value).isEqualTo(playbackBefore)
        assertThat(editor.first.hubState.value.undoAvailable).isEqualTo(undoBefore)

        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertThat(sheet.isVisible).isFalse()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_AREA)
        assertThat(currentArea(nav)).isEqualTo("nowPlaying")
    }

    @Test
    fun undoAffordanceIsHiddenOnTheRealGraphWhileTheSessionHasNoEntry() {
        setContent()
        openEditor()

        val appliedUndo = appContext().getString(R.string.style_editor_applied_undo)
        composeRule.onAllNodesWithText(appliedUndo).assertCountEquals(0)
    }

    @Test
    fun undoAffordanceIsHiddenWithoutAnEntryOnThePureHubScreen() {
        composeRule.setContent {
            OmnisonicTheme {
                Column {
                    StyleEditorHubScreen(
                        state = StyleEditorHubState(activeStyleName = "Default Style", undoAvailable = false),
                        onOpenArea = {},
                        onUndo = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(appContext().getString(R.string.style_editor_applied_undo))
            .assertCountEquals(0)
    }

    @Test
    fun undoAffordanceShowsAndDispatchesOnlyWhenAnEntryExists() {
        var undoClicks = 0
        composeRule.setContent {
            OmnisonicTheme {
                Column {
                    StyleEditorHubScreen(
                        state = StyleEditorHubState(activeStyleName = "Default Style", undoAvailable = true),
                        onOpenArea = {},
                        onUndo = { undoClicks++ },
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(appContext().getString(R.string.style_editor_applied_undo))
            .performClick()
        composeRule.waitForIdle()
        assertThat(undoClicks).isEqualTo(1)
    }

    @Test
    fun stylesAreaListsEveryStyleMarksOnlyTheActiveOneAndShowsOnePreviewCard() {
        val editor = testStyleEditorGraph("styles")
        val nav = setContent(editor.viewModel to editor.store)
        openEditor()

        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.STYLE_EDITOR_AREA)
        assertThat(currentArea(nav)).isEqualTo("styles")

        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Style preview: Default Style").assertExists()
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Apply Default Style, currently active").assertExists()
        composeRule.onNodeWithContentDescription("Apply Compact List").assertExists()

        composeRule.onNodeWithContentDescription("Apply Compact List").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Apply Compact List, currently active").assertExists()
        composeRule.onNodeWithContentDescription("Apply Default Style").assertExists()
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)
        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Style preview: Compact List").assertExists()
    }

    @Test
    fun stylesPreviewCardFollowsTheActiveStyleAppliedElsewhere() {
        val editor = testStyleEditorGraph("stylesfollow")
        setContent(editor.viewModel to editor.store)
        openEditor()

        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { runBlocking { editor.store.switch("builtin-large-cover") } }
        composeRule.waitForIdle()

        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Style preview: Large Cover").assertExists()
        composeRule.onNodeWithContentDescription("Apply Large Cover, currently active").assertExists()
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)
    }

    @Test
    fun stylesAreaOffersDuplicateOnEveryRowAndRenameOnlyOnUserStyles() {
        val editor = testStyleEditorGraph("lifecycle")
        editor.repository.write(StyleDocument(id = "mine", name = "Mine"))
        setContent(editor.viewModel to editor.store)
        openEditor()

        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodes(duplicateAction()).assertCountEquals(8)
        composeRule.onAllNodes(renameAction()).assertCountEquals(1)
        composeRule.onAllNodes(deleteAction()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Delete Mine").assertExists()
        composeRule.onNodeWithContentDescription("Rename Mine").assertExists()
        composeRule.onNodeWithContentDescription("Duplicate Default Style").assertExists()
        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Apply Default Style, currently active")
            .assertExists()

        val texts = composeRule.onAllNodes(hasText()).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }
        assertThat(texts.any { it.contains("Damaged", ignoreCase = true) }).isFalse()
        assertThat(texts.any { it.contains("Undo", ignoreCase = true) }).isFalse()
    }

    @Test
    fun deletingAUserStyleFromTheStylesScreenAsksFirstAndThenRemovesIt() {
        val editor = testStyleEditorGraph("deleteui")
        editor.repository.write(StyleDocument(id = "mine", name = "Mine"))
        setContent(editor.viewModel to editor.store)
        openEditor()
        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Delete Mine")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Delete Mine?").assertExists()
        composeRule.onNodeWithText(
            "This permanently deletes the style file. It cannot be recovered from the app.",
        ).assertExists()
        assertThat(runBlocking { editor.repository.list().map { it.id } }).contains("mine")

        composeRule.onNodeWithText("Delete style").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Apply Mine").assertDoesNotExist()
        composeRule.onAllNodes(deleteAction()).assertCountEquals(0)
        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Style preview: Default Style").assertExists()
        assertThat(runBlocking { editor.repository.list().map { it.id } }).doesNotContain("mine")
    }

    @Test
    fun damagedStyleIsSurfacedWithOnlyTheSafeRecoveryActions() {
        val editor = testStyleEditorGraph("damagedui")
        editor.stylesDir.mkdirs()
        File(editor.stylesDir, "broken${StyleRepository.FILE_SUFFIX}")
            .writeText("{ not a style\n")
        // One ordinary write re-lists, so the route observes the damaged file.
        editor.repository.write(StyleDocument(id = "mine", name = "Mine"))
        setContent(editor.viewModel to editor.store)
        openEditor()
        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Needs attention").assertExists()
        composeRule.onNodeWithContentDescription("Style needing attention: broken").assertExists()
        composeRule.onNodeWithContentDescription("Apply broken").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Export the raw file for broken").assertExists()
        composeRule.onNodeWithContentDescription("Reset broken to the default style").assertExists()
        composeRule.onNodeWithContentDescription("Delete broken").assertExists()
        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)

        composeRule.onNodeWithContentDescription("Export the raw file for broken")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Raw file ready", substring = true).assertExists()
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Raw file ready", substring = true).assertDoesNotExist()
    }

    @Test
    fun duplicatingABuiltInFromTheStylesScreenAddsACopyWithoutSwitching() {
        val editor = testStyleEditorGraph("duplicateui")
        setContent(editor.viewModel to editor.store)
        openEditor()
        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Duplicate Default Style").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Apply Default Style copy").assertExists()
        composeRule.onNodeWithContentDescription("Apply Default Style, currently active")
            .assertExists()
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)
        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Style preview: Default Style").assertExists()
    }

    @Test
    fun renamingAUserStyleFromTheDialogUpdatesTheRowAndKeepsItActive() {
        val editor = testStyleEditorGraph("renameui")
        editor.repository.write(StyleDocument(id = "mine", name = "Mine"))
        runBlocking { editor.store.switch("mine") }
        setContent(editor.viewModel to editor.store)
        openEditor()
        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Rename Mine")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNode(editableField()).assertTextContains("Mine")
        composeRule.onNode(editableField()).performTextReplacement("Mine renamed")
        composeRule.onNodeWithText("Rename style").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Apply Mine renamed, currently active")
            .assertExists()
        composeRule.onAllNodesWithText("Active").assertCountEquals(1)
        composeRule.onAllNodes(previewCard()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Style preview: Mine renamed").assertExists()
        composeRule.onNodeWithContentDescription("Rename Mine renamed").assertExists()
    }

    @Test
    fun noEditorCopyPromisesStateTheAppDoesNotHave() {
        setContent()
        openEditor()

        composeRule.onNode(areaRow("Styles")).performClick()
        composeRule.waitForIdle()

        val texts = composeRule.onAllNodes(hasText()).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text.lowercase() }
        listOf("unsaved", "previewing", "saves as you change").forEach { promise ->
            assertThat(texts.any { it.contains(promise) }).isFalse()
        }
    }
}
