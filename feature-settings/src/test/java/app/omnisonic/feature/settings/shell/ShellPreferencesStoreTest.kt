package app.omnisonic.feature.settings.shell

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.omnisonic.style.NavigationLayout
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ShellPreferencesStoreTest {

    @TempDir
    lateinit var tempDir: File

    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newScope(): CoroutineScope {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return scope!!
    }

    private fun newStore(): ShellPreferencesStore = ShellPreferencesStore(
        PreferenceDataStoreFactory.create(
            scope = newScope(),
            produceFile = { File(tempDir, "shell-${System.nanoTime()}.preferences_pb") },
        ),
    )

    /**
     * Seeds the raw steps key through a throwaway DataStore so store-level tests
     * can start from non-default step lists (a Phase 9 tutorial insertion).
     */
    private suspend fun storeWithSteps(steps: List<String>, file: File): ShellPreferencesStore {
        val seedJob = SupervisorJob()
        val seedScope = CoroutineScope(seedJob + Dispatchers.IO)
        val seed = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { file })
        seed.edit {
            it[stringPreferencesKey("shell.onboarding_steps")] = steps.joinToString(",")
        }
        seedJob.cancel()
        withTimeoutOrNull(2_000) { while (seedJob.children.any()) delay(10) }
        return ShellPreferencesStore(
            PreferenceDataStoreFactory.create(scope = newScope(), produceFile = { file }),
        )
    }

    @Test
    fun `fresh store yields defaults`() = runTest {
        val store = newStore()

        val prefs = store.preferences.first()

        assertThat(prefs.visibleTabs).isEqualTo(MainTab.DEFAULT)
        assertThat(prefs.portraitNavOverride).isNull()
        assertThat(prefs.landscapeNavOverride).isNull()
        assertThat(prefs.onboardingSteps)
            .isEqualTo(ShellPreferencesStore.DEFAULT_ONBOARDING_STEPS)
        assertThat(prefs.onboardingComplete).isFalse()
    }

    @Test
    fun `visible tabs persist and empty writes fall back to defaults`() = runTest {
        val store = newStore()

        store.setVisibleTabs(listOf(MainTab.SEARCH, MainTab.HOME))
        assertThat(store.preferences.first().visibleTabs)
            .containsExactly(MainTab.SEARCH, MainTab.HOME).inOrder()

        store.setVisibleTabs(emptyList())
        assertThat(store.preferences.first().visibleTabs).isEqualTo(MainTab.DEFAULT)
    }

    @Test
    fun `nav overrides set and clear per orientation`() = runTest {
        val store = newStore()

        store.setNavOverride(false, NavigationLayout.RAIL)
        store.setNavOverride(true, NavigationLayout.BOTTOM_BAR)
        store.preferences.first().let { prefs ->
            assertThat(prefs.portraitNavOverride).isEqualTo(NavigationLayout.RAIL)
            assertThat(prefs.landscapeNavOverride).isEqualTo(NavigationLayout.BOTTOM_BAR)
        }

        store.setNavOverride(false, null)
        store.preferences.first().let { prefs ->
            assertThat(prefs.portraitNavOverride).isNull()
            assertThat(prefs.landscapeNavOverride).isEqualTo(NavigationLayout.BOTTOM_BAR)
        }
    }

    @Test
    fun `completing the last onboarding step exhausts the list and sets the flag (D-12)`() = runTest {
        val store = newStore()

        store.completeOnboardingStep(OnboardingSteps.PROVIDER_SETUP)
        val mid = store.preferences.first()
        assertThat(mid.onboardingSteps).containsExactly(OnboardingSteps.LIBRARY_SYNC)
        assertThat(mid.onboardingComplete).isFalse()
        assertThat(OnboardingGate.startRoute(mid)).isEqualTo(OnboardingSteps.LIBRARY_SYNC)

        store.completeOnboardingStep(OnboardingSteps.LIBRARY_SYNC)

        val prefs = store.preferences.first()
        assertThat(prefs.onboardingSteps).isEmpty()
        assertThat(prefs.onboardingComplete).isTrue()
        assertThat(OnboardingGate.startRoute(prefs)).isEqualTo(OnboardingGate.HOME_ROUTE)
    }

    @Test
    fun `completing a step that is not last keeps the gate on the next step`() = runTest {
        val store = storeWithSteps(
            listOf(OnboardingSteps.PROVIDER_SETUP, "tour"),
            File(tempDir, "steps.preferences_pb"),
        )

        store.completeOnboardingStep(OnboardingSteps.PROVIDER_SETUP)

        val prefs = store.preferences.first()
        assertThat(prefs.onboardingSteps).containsExactly("tour")
        assertThat(prefs.onboardingComplete).isFalse()
        assertThat(OnboardingGate.startRoute(prefs)).isEqualTo("tour")
    }

    @Test
    fun `preferences persist across store instances on one file`() = runTest {
        val file = File(tempDir, "shared.preferences_pb")
        val firstJob = SupervisorJob()
        scope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = ShellPreferencesStore(
            PreferenceDataStoreFactory.create(scope = scope!!, produceFile = { file }),
        )
        first.setNavOverride(true, NavigationLayout.RAIL)
        first.completeOnboardingStep(OnboardingSteps.PROVIDER_SETUP)
        first.completeOnboardingStep(OnboardingSteps.LIBRARY_SYNC)
        scope?.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }

        val second = ShellPreferencesStore(
            PreferenceDataStoreFactory.create(scope = newScope(), produceFile = { file }),
        )
        val prefs = second.preferences.first()
        assertThat(prefs.landscapeNavOverride).isEqualTo(NavigationLayout.RAIL)
        assertThat(prefs.onboardingComplete).isTrue()
    }
}
