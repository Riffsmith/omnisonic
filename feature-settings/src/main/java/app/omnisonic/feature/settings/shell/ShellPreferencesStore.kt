package app.omnisonic.feature.settings.shell

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.omnisonic.style.NavigationLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Onboarding step identifiers. Values are shell-internal step names that double
 * as their navigation routes (reference/docs/UI_SCREENS.md §5). Extend this
 * list with new steps only — the gate itself never changes (D-12).
 */
object OnboardingSteps {
    const val PROVIDER_SETUP = "providerSetup"
    const val LIBRARY_SYNC = "librarySync"
}

data class ShellPreferences(
    val visibleTabs: List<MainTab>,
    val portraitNavOverride: NavigationLayout?,
    val landscapeNavOverride: NavigationLayout?,
    val onboardingSteps: List<String>,
    val onboardingComplete: Boolean,
)

/**
 * Shell-wide user preferences (tabs, per-orientation nav overrides, onboarding
 * state). Token defaults live in `:style`; this store only holds explicit user
 * overrides, so a `null` override keeps the token value authoritative.
 *
 * Onboarding (D-12): the ordered [ShellPreferences.onboardingSteps] list starts
 * at [DEFAULT_ONBOARDING_STEPS]; [completeOnboardingStep] removes a finished
 * step and sets `onboardingComplete` only when the list is exhausted.
 */
class ShellPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<ShellPreferences> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            ShellPreferences(
                visibleTabs = MainTabSet.parse(prefs[Keys.VISIBLE_TABS]),
                portraitNavOverride = prefs[Keys.NAV_OVERRIDE_PORTRAIT]?.let(::readNavLayout),
                landscapeNavOverride = prefs[Keys.NAV_OVERRIDE_LANDSCAPE]?.let(::readNavLayout),
                onboardingSteps = parseSteps(prefs[Keys.ONBOARDING_STEPS]),
                onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            )
        }

    suspend fun setVisibleTabs(tabs: Collection<MainTab>) {
        val sanitised = MainTabSet.parse(MainTabSet.serialize(tabs))
        dataStore.edit { it[Keys.VISIBLE_TABS] = MainTabSet.serialize(sanitised) }
    }

    suspend fun setNavOverride(isLandscape: Boolean, layout: NavigationLayout?) {
        dataStore.edit { prefs ->
            val key = if (isLandscape) Keys.NAV_OVERRIDE_LANDSCAPE else Keys.NAV_OVERRIDE_PORTRAIT
            if (layout == null) prefs.remove(key) else prefs[key] = layout.name
        }
    }

    suspend fun completeOnboardingStep(step: String) {
        dataStore.edit { prefs ->
            val remaining = parseSteps(prefs[Keys.ONBOARDING_STEPS]) - step
            prefs[Keys.ONBOARDING_STEPS] = remaining.joinToString(STEP_SEPARATOR)
            prefs[Keys.ONBOARDING_COMPLETE] = remaining.isEmpty()
        }
    }

    private fun readNavLayout(name: String): NavigationLayout? =
        NavigationLayout.entries.firstOrNull { it.name == name }

    private object Keys {
        val VISIBLE_TABS = stringPreferencesKey("shell.tab_order")
        val NAV_OVERRIDE_PORTRAIT = stringPreferencesKey("shell.nav_override_portrait")
        val NAV_OVERRIDE_LANDSCAPE = stringPreferencesKey("shell.nav_override_landscape")
        val ONBOARDING_STEPS = stringPreferencesKey("shell.onboarding_steps")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("shell.onboarding_complete")
    }

    companion object {
        val DEFAULT_ONBOARDING_STEPS = listOf(
            OnboardingSteps.PROVIDER_SETUP,
            OnboardingSteps.LIBRARY_SYNC,
        )
        const val STEP_SEPARATOR = ","

        fun create(context: Context): ShellPreferencesStore = ShellPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("omnisonic_shell") },
            ),
        )

        fun createAt(file: File): ShellPreferencesStore = ShellPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
        )
    }
}

private fun parseSteps(raw: String?): List<String> {
    if (raw == null) return ShellPreferencesStore.DEFAULT_ONBOARDING_STEPS.toList()
    return raw.split(ShellPreferencesStore.STEP_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
