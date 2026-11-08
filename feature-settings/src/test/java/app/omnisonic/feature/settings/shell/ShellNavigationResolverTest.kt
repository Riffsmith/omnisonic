package app.omnisonic.feature.settings.shell

import app.omnisonic.style.NavigationLayout
import app.omnisonic.style.DefaultStyleTokens
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ShellNavigationResolverTest {

    private val tokens = DefaultStyleTokens.layout

    @Test
    fun `no override falls through to token defaults per orientation`() {
        assertThat(
            ShellNavigationResolver.resolve(tokens, null, null, isLandscape = false),
        ).isEqualTo(tokens.portraitNavigation)
        assertThat(
            ShellNavigationResolver.resolve(tokens, null, null, isLandscape = true),
        ).isEqualTo(tokens.landscapeNavigation)
    }

    @Test
    fun `override wins only for its own orientation`() {
        assertThat(
            ShellNavigationResolver.resolve(
                tokens,
                portraitOverride = NavigationLayout.RAIL,
                landscapeOverride = null,
                isLandscape = false,
            ),
        ).isEqualTo(NavigationLayout.RAIL)
        assertThat(
            ShellNavigationResolver.resolve(
                tokens,
                portraitOverride = NavigationLayout.RAIL,
                landscapeOverride = null,
                isLandscape = true,
            ),
        ).isEqualTo(tokens.landscapeNavigation)
    }

    @Test
    fun `landscape override wins in landscape`() {
        assertThat(
            ShellNavigationResolver.resolve(
                tokens,
                portraitOverride = null,
                landscapeOverride = NavigationLayout.BOTTOM_BAR,
                isLandscape = true,
            ),
        ).isEqualTo(NavigationLayout.BOTTOM_BAR)
    }
}

class OnboardingGateTest {

    private fun prefs(
        steps: List<String> = ShellPreferencesStore.DEFAULT_ONBOARDING_STEPS,
        complete: Boolean = false,
    ) = ShellPreferences(
        visibleTabs = MainTab.DEFAULT,
        portraitNavOverride = null,
        landscapeNavOverride = null,
        onboardingSteps = steps,
        onboardingComplete = complete,
    )

    @Test
    fun `flag unset lands on the first remaining step (fresh install to providerSetup)`() {
        assertThat(OnboardingGate.startRoute(prefs()))
            .isEqualTo(OnboardingSteps.PROVIDER_SETUP)
    }

    @Test
    fun `flag set lands on home (later launches)`() {
        assertThat(OnboardingGate.startRoute(prefs(complete = true)))
            .isEqualTo(OnboardingGate.HOME_ROUTE)
    }

    @Test
    fun `an inserted step routes before the later ones without touching the gate`() {
        val withTutorial = prefs(steps = listOf("tour", OnboardingSteps.PROVIDER_SETUP))
        assertThat(OnboardingGate.startRoute(withTutorial)).isEqualTo("tour")
    }

    @Test
    fun `empty step list with flag unset degrades to home`() {
        assertThat(OnboardingGate.startRoute(prefs(steps = emptyList())))
            .isEqualTo(OnboardingGate.HOME_ROUTE)
    }
}

class TabOrderingTest {

    @Test
    fun `hiding removes the tab`() {
        val tabs = TabOrdering.setVisible(MainTab.DEFAULT, MainTab.SEARCH, visible = false)
        assertThat(tabs).containsExactly(MainTab.HOME, MainTab.LIBRARY, MainTab.SETTINGS).inOrder()
    }

    @Test
    fun `showing re-inserts at the default-order position`() {
        val tabs = TabOrdering.setVisible(listOf(MainTab.HOME, MainTab.SETTINGS), MainTab.LIBRARY, visible = true)
        assertThat(tabs).containsExactly(MainTab.HOME, MainTab.LIBRARY, MainTab.SETTINGS).inOrder()
    }

    @Test
    fun `appending a tab later in default order goes to the end`() {
        val tabs = TabOrdering.setVisible(listOf(MainTab.HOME, MainTab.LIBRARY), MainTab.SETTINGS, visible = true)
        assertThat(tabs).containsExactly(MainTab.HOME, MainTab.LIBRARY, MainTab.SETTINGS).inOrder()
    }

    @Test
    fun `move swaps with the neighbour in the given direction`() {
        val tabs = listOf(MainTab.HOME, MainTab.LIBRARY, MainTab.SEARCH)
        assertThat(TabOrdering.move(tabs, MainTab.LIBRARY, offset = -1))
            .containsExactly(MainTab.LIBRARY, MainTab.HOME, MainTab.SEARCH).inOrder()
        assertThat(TabOrdering.move(tabs, MainTab.LIBRARY, offset = +1))
            .containsExactly(MainTab.HOME, MainTab.SEARCH, MainTab.LIBRARY).inOrder()
    }

    @Test
    fun `move clamps at the list bounds`() {
        val tabs = listOf(MainTab.HOME, MainTab.LIBRARY)
        assertThat(TabOrdering.move(tabs, MainTab.HOME, offset = -1))
            .containsExactly(MainTab.HOME, MainTab.LIBRARY).inOrder()
        assertThat(TabOrdering.move(tabs, MainTab.LIBRARY, offset = +5))
            .containsExactly(MainTab.HOME, MainTab.LIBRARY).inOrder()
    }

    @Test
    fun `moving an absent tab is a no-op`() {
        val tabs = listOf(MainTab.HOME)
        assertThat(TabOrdering.move(tabs, MainTab.SEARCH, offset = 1)).isEqualTo(tabs)
    }
}
