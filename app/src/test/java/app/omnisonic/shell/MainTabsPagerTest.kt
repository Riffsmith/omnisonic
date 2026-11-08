package app.omnisonic.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.OnboardingGate
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.style.ButtonTwinAudit
import app.omnisonic.style.DefaultStyleTokens
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.StyleTokens
import app.omnisonic.style.TabSwipeMode
import app.omnisonic.style.gestureTwins
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.dp
import java.io.File

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

private fun isSelected(): SemanticsMatcher = SemanticsMatcher("is selected") { node ->
    node.config.getOrNull(SemanticsProperties.Selected) == true
}

private fun isNotSelected(): SemanticsMatcher = SemanticsMatcher("is not selected") { node ->
    node.config.getOrNull(SemanticsProperties.Selected) != true
}

/** Bottom-bar items carry Role.Tab; the settings screen's tab rows do not. */
private fun tabItem(label: String): SemanticsMatcher = SemanticsMatcher("tab '$label'") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
}

private fun isTab(): SemanticsMatcher = SemanticsMatcher("tab item") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Tab
}

private fun preferencesStore(tag: String): ShellPreferencesStore =
    ShellPreferencesStore.createAt(File("/tmp/opencode", "$tag-${System.nanoTime()}.preferences_pb"))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainTabsPagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: ShellUiState = fixedShellState(),
        tokens: StyleTokens = DefaultStyleTokens,
    ): NavHostController {
        lateinit var nav: NavHostController
        val settingsViewModel = ShellSettingsViewModel(preferencesStore("pager"))
        val providerSetupViewModel = testProviderSetupViewModel()
        composeRule.setContent {
            OmnisonicTheme(tokens = tokens) {
                OmnisonicShell(
                    state = state,
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                    navController = rememberNavController().also { nav = it },
                )
            }
        }
        composeRule.waitForIdle()
        return nav
    }

    private fun swipeLeftFromCenter() {
        composeRule.onRoot().performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x - width * 0.7f, center.y))
        }
        composeRule.waitForIdle()
    }

    private fun swipeLeftFrom(startX: Dp) {
        composeRule.onRoot().performTouchInput {
            swipe(Offset(startX.toPx(), center.y), Offset(startX.toPx() - width * 0.7f, center.y))
        }
        composeRule.waitForIdle()
    }

    @Test
    fun contentSwipeAdvancesToTheNextTab() {
        setContent()

        swipeLeftFromCenter()

        composeRule.onNode(tabItem("Library")).assert(isSelected())
        composeRule.onNode(tabItem("Home")).assert(isNotSelected())
    }

    @Test
    fun swipeSettleFollowsTheRoute() {
        val nav = setContent()

        swipeLeftFromCenter()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY)
    }

    @Test
    fun routeArrivalSnapsTheIdlePager() {
        val nav = setContent()

        composeRule.runOnIdle { nav.navigate(ShellRoutes.SEARCH) { launchSingleTop = true } }
        composeRule.waitForIdle()

        composeRule.onNode(tabItem("Search")).assert(isSelected())
        composeRule.onNode(tabItem("Home")).assert(isNotSelected())
    }

    @Test
    fun edgeExclusionVetoesDragsInsideTheStrip() {
        setContent()

        swipeLeftFrom(12.dp)

        composeRule.onNode(tabItem("Home")).assert(isSelected())
        composeRule.onNode(tabItem("Library")).assert(isNotSelected())
    }

    @Test
    fun edgeExclusionAllowsDragsOutsideTheStrip() {
        setContent()

        swipeLeftFrom(32.dp)

        composeRule.onNode(tabItem("Library")).assert(isSelected())
    }

    @Test
    fun disabledSwipeModeKeepsTabsOnly() {
        val tokens = DefaultStyleTokens.copy(
            layout = DefaultStyleTokens.layout.copy(portraitTabSwipeMode = TabSwipeMode.DISABLED),
        )
        setContent(tokens = tokens)

        swipeLeftFromCenter()
        composeRule.onNode(tabItem("Home")).assert(isSelected())

        composeRule.onNode(tabItem("Settings")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Version").assertExists()
        composeRule.onNode(tabItem("Settings")).assert(isSelected())
    }

    @Test
    fun tabSwipeTwinsPassTheAudit() {
        setContent()

        val labels = composeRule.onAllNodes(isTab()).fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)!!.first().text }
        assertThat(labels).hasSize(MainTab.DEFAULT.size)
        val twins = MainTab.DEFAULT.mapIndexed { index, tab ->
            GestureTwin(
                gestureId = tab.id,
                actionLabel = "Go to ${labels[index]}",
                twinControlLabel = labels[index],
            )
        }

        ButtonTwinAudit.assertTwinsPresent(composeRule.onRoot().fetchSemanticsNode(), twins)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ButtonTwinAuditTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val twin = GestureTwin(
        gestureId = "demoGesture",
        actionLabel = "Do the thing",
        twinControlLabel = "Do the thing",
    )

    @Test
    fun passesWhenActionAndVisibleControlExist() {
        composeRule.setContent {
            Column {
                Box(Modifier.fillMaxSize().gestureTwins(listOf(twin)) { })
                Button(onClick = {}) { Text("Do the thing") }
            }
        }
        composeRule.waitForIdle()

        val missing = ButtonTwinAudit.findMissingTwins(
            composeRule.onRoot().fetchSemanticsNode(),
            listOf(twin),
        )
        assertThat(missing).isEmpty()
    }

    @Test
    fun failsWhenTheVisibleControlIsMissing() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().gestureTwins(listOf(twin)) { })
        }
        composeRule.waitForIdle()

        val root = composeRule.onRoot().fetchSemanticsNode()
        assertThat(ButtonTwinAudit.findMissingTwins(root, listOf(twin))).containsExactly(twin)
        val thrown = runCatching { ButtonTwinAudit.assertTwinsPresent(root, listOf(twin)) }
            .exceptionOrNull()
        assertThat(thrown).isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun failsWhenTheCustomActionIsMissing() {
        composeRule.setContent {
            Button(onClick = {}) { Text("Do the thing") }
        }
        composeRule.waitForIdle()

        val missing = ButtonTwinAudit.findMissingTwins(
            composeRule.onRoot().fetchSemanticsNode(),
            listOf(twin),
        )
        assertThat(missing).containsExactly(twin)
    }
}
