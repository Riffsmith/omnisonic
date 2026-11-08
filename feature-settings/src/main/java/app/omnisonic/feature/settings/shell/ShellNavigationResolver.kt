package app.omnisonic.feature.settings.shell

import app.omnisonic.style.LayoutTokens
import app.omnisonic.style.NavigationLayout

/**
 * First-run landing decision (D-12): an onboarding-completed shell lands on
 * `home`; an incomplete one lands on the first remaining step of the ordered
 * onboarding list. `home` is expressed as a route string so the gate stays
 * navigation-agnostic; today the only steps route to `providerSetup`.
 */
object OnboardingGate {

    const val HOME_ROUTE = "home"

    fun startRoute(prefs: ShellPreferences): String {
        if (prefs.onboardingComplete) return HOME_ROUTE
        return prefs.onboardingSteps.firstOrNull() ?: HOME_ROUTE
    }
}

/**
 * Nav layout per orientation: an explicit user override wins, otherwise the
 * token default stays authoritative (tokens are the single source of layout
 * values — docs/omnisonic/ui/style-tokens.md).
 */
object ShellNavigationResolver {

    fun resolve(
        tokens: LayoutTokens,
        portraitOverride: NavigationLayout?,
        landscapeOverride: NavigationLayout?,
        isLandscape: Boolean,
    ): NavigationLayout {
        val override = if (isLandscape) landscapeOverride else portraitOverride
        return override ?: tokens.navigationFor(isLandscape)
    }
}
