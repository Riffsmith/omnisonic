package app.omnisonic.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.OnboardingGate
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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

private fun concreteRoute(pattern: String): String = when (pattern) {
    ShellRoutes.SECTION -> ShellRoutes.section("albums")
    ShellRoutes.ALBUM -> ShellRoutes.album(7L)
    ShellRoutes.ARTIST -> ShellRoutes.artist(7L)
    ShellRoutes.GENRE -> ShellRoutes.genre(7L)
    ShellRoutes.PLAYLIST -> ShellRoutes.playlist(7L)
    else -> pattern
}

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellNavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setNavHost(startRoute: String): NavHostController {
        lateinit var nav: NavHostController
        val settingsViewModel = ShellSettingsViewModel(
            ShellPreferencesStore.createAt(File("/tmp/opencode", "navhost-${System.nanoTime()}.preferences_pb")),
        )
        val providerSetupViewModel = testProviderSetupViewModel()
        composeRule.setContent {
            OmnisonicTheme {
                ShellNavHost(
                    navController = rememberNavController().also { nav = it },
                    startRoute = startRoute,
                    hasProvider = true,
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                    browseViewModel = testBrowseViewModel(),
                    modifier = Modifier,
                )
            }
        }
        composeRule.waitForIdle()
        return nav
    }

    @Test
    fun everyRouteTableRouteResolves() {
        val nav = setNavHost(ShellRoutes.HOME)

        ShellRoutes.ALL.forEach { pattern ->
            composeRule.runOnIdle { nav.navigate(concreteRoute(pattern)) { launchSingleTop = true } }
            composeRule.waitForIdle()
            assertThat(nav.currentDestination?.route).isEqualTo(pattern)
        }
    }

    @Test
    fun onboardingStartRouteLandsOnProviderSetup() {
        val nav = setNavHost(ShellRoutes.PROVIDER_SETUP)
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.PROVIDER_SETUP)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ShellTabNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(hasProvider: Boolean = true): NavHostController {
        val settingsViewModel = ShellSettingsViewModel(
            ShellPreferencesStore.createAt(File("/tmp/opencode", "tabnav-${System.nanoTime()}.preferences_pb")),
        )
        val providerSetupViewModel = testProviderSetupViewModel()
        lateinit var nav: NavHostController
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = fixedShellState(hasProvider = hasProvider),
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                    browseViewModel = testBrowseViewModel(),
                    navController = rememberNavController().also { nav = it },
                )
            }
        }
        composeRule.waitForIdle()
        return nav
    }

    @Test
    fun tabTapSwitchesDestinationAndMarksTabSelected() {
        setContent()

        composeRule.onNode(tabItem("Settings")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Version").assertExists()
        composeRule.onNode(tabItem("Settings")).assert(isSelected())
        composeRule.onNode(tabItem("Home")).assert(isNotSelected())
    }

    @Test
    fun homeTabTapRestoresHomePane() {
        setContent(hasProvider = false)

        composeRule.onNode(tabItem("Settings")).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(tabItem("Home")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Set up a provider").assertExists()
    }

    @Test
    fun libraryHubEntryOpensTheSectionRoute() {
        val nav = setContent()

        composeRule.onNode(tabItem("Library")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Albums").performClick()
        composeRule.waitForIdle()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.SECTION)
    }
}
