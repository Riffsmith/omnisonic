package app.omnisonic.feature.settings.sync

import app.omnisonic.core.data.sync.LibrarySyncPhase
import app.omnisonic.feature.settings.shell.OnboardingSteps
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySyncStepViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `live walk progress maps to the step state`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProgress = runningProgress(),
            initialProviders = listOf(provider(id = 1)),
        )

        val viewModel = LibrarySyncStepViewModel(control, shellStore())

        val state = viewModel.state.value
        assertThat(state.loaded).isTrue()
        assertThat(state.providerId).isEqualTo(1)
        assertThat(state.phase).isEqualTo(LibrarySyncPhase.ALBUMS_SONGS)
        assertThat(state.isRunning).isTrue()
        assertThat(state.canContinue).isFalse()
    }

    @Test
    fun `completion enables Continue`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncStepViewModel(control, shellStore())
        assertThat(viewModel.state.value.canContinue).isFalse()

        control.publish(terminalProgress(LibrarySyncPhase.DONE))
        runCurrent()

        assertThat(viewModel.state.value.isComplete).isTrue()
        assertThat(viewModel.state.value.canContinue).isTrue()
    }

    @Test
    fun `failure offers Retry and Continue-anyway`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncStepViewModel(control, shellStore())

        control.publish(terminalProgress(LibrarySyncPhase.FAILED))
        runCurrent()
        assertThat(viewModel.state.value.isFailed).isTrue()
        assertThat(viewModel.state.value.canContinue).isTrue()

        val callsBefore = control.fullSyncCalls.size
        viewModel.retry()

        assertThat(control.fullSyncCalls).hasSize(callsBefore + 1)
        assertThat(control.fullSyncCalls.last()).isEqualTo(1L)
    }

    @Test
    fun `blocked and skipped phases keep the step continuable`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncStepViewModel(control, shellStore())

        control.publish(terminalProgress(LibrarySyncPhase.BLOCKED))
        runCurrent()
        assertThat(viewModel.state.value.isFailed).isTrue()

        control.publish(terminalProgress(LibrarySyncPhase.SKIPPED))
        runCurrent()
        assertThat(viewModel.state.value.isFailed).isTrue()
        assertThat(viewModel.state.value.canContinue).isTrue()
    }

    @Test
    fun `continue completes the onboarding step and emits completion once`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProgress = terminalProgress(LibrarySyncPhase.DONE),
            initialProviders = listOf(provider(id = 1)),
        )
        val store = mockk<ShellPreferencesStore>(relaxed = true)
        val viewModel = LibrarySyncStepViewModel(control, store)
        val events = mutableListOf<LibrarySyncStepEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        viewModel.continueIntoApp()
        viewModel.continueIntoApp()
        runCurrent()

        coVerify(exactly = 1) { store.completeOnboardingStep(OnboardingSteps.LIBRARY_SYNC) }
        assertThat(events).containsExactly(LibrarySyncStepEvent.Completed)
    }

    @Test
    fun `a recorded complete sync is adopted without starting a walk`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1, lastFullSyncAt = 9_000)),
        )

        val viewModel = LibrarySyncStepViewModel(control, shellStore())

        assertThat(control.fullSyncCalls).isEmpty()
        assertThat(viewModel.state.value.isComplete).isTrue()
        assertThat(viewModel.state.value.canContinue).isTrue()
    }

    @Test
    fun `a recorded incomplete sync restarts the walk`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )

        val viewModel = LibrarySyncStepViewModel(control, shellStore())

        assertThat(control.fullSyncCalls).containsExactly(1L)
        assertThat(viewModel.state.value.canContinue).isFalse()
    }

    @Test
    fun `no provider leaves the step continuable`() = runTest {
        val control = FakeLibrarySyncControl()

        val viewModel = LibrarySyncStepViewModel(control, shellStore())

        assertThat(viewModel.state.value.providerId).isNull()
        assertThat(viewModel.state.value.canContinue).isTrue()
        assertThat(control.fullSyncCalls).isEmpty()
    }

    private fun runCurrent() {
        mainDispatcher.scheduler.runCurrent()
    }

    private fun shellStore(): ShellPreferencesStore = mockk(relaxed = true)
}
