package app.omnisonic.feature.settings.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.omnisonic.core.data.providers.ProviderProbeResult
import app.omnisonic.core.data.providers.ProviderSetupForm
import app.omnisonic.core.data.providers.ProviderSetupIssue
import app.omnisonic.core.data.providers.ProviderSetupOutcome
import app.omnisonic.core.data.providers.ProviderSetupService
import app.omnisonic.feature.settings.shell.OnboardingSteps
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.provider.api.ProviderCapabilities
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.viewModelScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSetupViewModelTest {

    @TempDir
    lateinit var tempDir: File

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var storeScope: CoroutineScope
    private lateinit var store: ShellPreferencesStore
    private lateinit var service: ProviderSetupService
    private lateinit var viewModel: ProviderSetupViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = ShellPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = storeScope,
                produceFile = { File(tempDir, "provider-setup-${System.nanoTime()}.preferences_pb") },
            ),
        )
        service = mockk(relaxed = true)
        viewModel = ProviderSetupViewModel(service, store)
    }

    @AfterEach
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        storeScope.cancel()
        runBlocking {
            withTimeoutOrNull(2_000) {
                while (storeScope.coroutineContext[Job]?.children?.any() == true) delay(10)
            }
        }
        Dispatchers.resetMain()
    }

    private fun fillValid() {
        viewModel.onServerUrlChange("https://music.example.com")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("hunter2")
    }

    private fun success() = ProviderProbeResult.Success(ProviderCapabilities(serverSoftwareHint = "navidrome"))

    @Test
    fun `saving an empty form reports field issues and never probes`() {
        viewModel.save()

        assertThat(viewModel.state.value.issues).containsAtLeast(
            ProviderSetupIssue.SERVER_URL_REQUIRED,
            ProviderSetupIssue.USERNAME_REQUIRED,
            ProviderSetupIssue.PASSWORD_REQUIRED,
        )
        coVerify(exactly = 0) { service.probe(any()) }
        coVerify(exactly = 0) { service.save(any()) }
    }

    @Test
    fun `a URL without a scheme is a field error`() {
        viewModel.onServerUrlChange("music.example.com")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("hunter2")

        viewModel.save()

        assertThat(viewModel.state.value.issues).containsExactly(ProviderSetupIssue.SERVER_URL_INVALID)
        coVerify(exactly = 0) { service.probe(any()) }
    }

    @Test
    fun `test connection reports success`() {
        coEvery { service.probe(any()) } returns success()
        fillValid()

        viewModel.testConnection()

        assertThat(viewModel.state.value.connectionStatus).isEqualTo(ProviderConnectionStatus.OK)
        assertThat(viewModel.state.value.busy).isEqualTo(ProviderSetupBusy.NONE)
        coVerify(exactly = 1) { service.probe(any()) }
    }

    @Test
    fun `a rejected login stops the save before anything is persisted`() = runTest {
        coEvery { service.probe(any()) } returns
            ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.AUTHENTICATION)
        fillValid()

        viewModel.save()

        assertThat(viewModel.state.value.connectionStatus).isEqualTo(ProviderConnectionStatus.AUTH_ERROR)
        coVerify(exactly = 0) { service.save(any()) }
        verify(exactly = 0) { service.startInitialSync(any()) }
        assertThat(store.preferences.first().onboardingComplete).isFalse()
    }

    @Test
    fun `saving probes then persists, completes onboarding and starts the initial sync`() = runTest {
        coEvery { service.probe(any()) } returns success()
        coEvery { service.save(any()) } returns ProviderSetupOutcome.Saved(42L)
        val events = mutableListOf<ProviderSetupEvent>()
        val collector = launch(UnconfinedTestDispatcher(mainDispatcher.scheduler)) {
            viewModel.events.collect { events += it }
        }
        fillValid()

        viewModel.save()

        coVerify(exactly = 1) { service.probe(any()) }
        coVerify(exactly = 1) { service.save(any()) }
        verify(exactly = 1) { service.startInitialSync(42L) }
        assertThat(events).containsExactly(ProviderSetupEvent.Completed)
        // D-16: provider setup is the first of two steps; the flag waits for the
        // library-sync step.
        val prefs = store.preferences.first()
        assertThat(prefs.onboardingComplete).isFalse()
        assertThat(prefs.onboardingSteps).containsExactly(OnboardingSteps.LIBRARY_SYNC)
        assertThat(viewModel.state.value).isEqualTo(ProviderSetupUiState())
        collector.cancel()
    }

    @Test
    fun `a successful test is reused so save probes only once`() = runTest {
        coEvery { service.probe(any()) } returns success()
        coEvery { service.save(any()) } returns ProviderSetupOutcome.Saved(7L)
        fillValid()

        viewModel.testConnection()
        viewModel.save()

        coVerify(exactly = 1) { service.probe(any()) }
        coVerify(exactly = 1) { service.save(any()) }
    }

    @Test
    fun `editing a field invalidates the cached test result`() = runTest {
        coEvery { service.probe(any()) } returns success()
        coEvery { service.save(any()) } returns ProviderSetupOutcome.Saved(7L)
        fillValid()
        viewModel.testConnection()

        viewModel.onPasswordChange("correct-horse")
        viewModel.save()

        coVerify(exactly = 2) { service.probe(any()) }
    }

    @Test
    fun `the backup URL is sent when the toggle is on`() = runTest {
        coEvery { service.probe(any()) } returns success()
        val saved = mutableListOf<ProviderSetupForm>()
        coEvery { service.save(any()) } answers {
            saved += firstArg<ProviderSetupForm>()
            ProviderSetupOutcome.Saved(1L)
        }
        fillValid()
        viewModel.onUseSecondaryChange(true)
        viewModel.onSecondaryUrlChange("https://backup.example.com")

        viewModel.save()

        assertThat(saved.single().secondaryUrl).isEqualTo("https://backup.example.com")
    }

    @Test
    fun `turning the backup toggle off drops the secondary URL`() = runTest {
        coEvery { service.probe(any()) } returns success()
        val saved = mutableListOf<ProviderSetupForm>()
        coEvery { service.save(any()) } answers {
            saved += firstArg<ProviderSetupForm>()
            ProviderSetupOutcome.Saved(1L)
        }
        fillValid()
        viewModel.onUseSecondaryChange(true)
        viewModel.onSecondaryUrlChange("https://backup.example.com")
        viewModel.onUseSecondaryChange(false)

        viewModel.save()

        assertThat(saved.single().secondaryUrl).isEmpty()
        assertThat(viewModel.state.value.secondaryUrl).isEmpty()
    }

    @Test
    fun `completing setup again from settings keeps the onboarding flag`() = runTest {
        coEvery { service.probe(any()) } returns success()
        coEvery { service.save(any()) } returns ProviderSetupOutcome.Saved(9L)
        store.completeOnboardingStep(OnboardingSteps.PROVIDER_SETUP)
        store.completeOnboardingStep(OnboardingSteps.LIBRARY_SYNC)
        fillValid()

        viewModel.save()

        assertThat(store.preferences.first().onboardingComplete).isTrue()
    }

    @Test
    fun `cleartext on a public host raises the warning`() {
        viewModel.onServerUrlChange("http://music.example.com")

        assertThat(viewModel.state.value.cleartextWarning).isTrue()

        viewModel.onServerUrlChange("http://192.168.1.5:4533")
        assertThat(viewModel.state.value.cleartextWarning).isFalse()

        viewModel.onServerUrlChange("https://music.example.com")
        assertThat(viewModel.state.value.cleartextWarning).isFalse()
    }
}
