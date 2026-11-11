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
import kotlinx.coroutines.test.TestScope
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
    fun `terminal completion auto-advances`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val store = mockk<ShellPreferencesStore>(relaxed = true)
        val viewModel = LibrarySyncStepViewModel(control, store)
        val events = collectEvents(viewModel)
        assertThat(viewModel.state.value.canContinue).isFalse()

        control.publish(terminalProgress(LibrarySyncPhase.DONE))
        runCurrent()

        assertThat(viewModel.state.value.isComplete).isTrue()
        assertThat(viewModel.state.value.canContinue).isTrue()
        coVerify(exactly = 1) { store.completeOnboardingStep(OnboardingSteps.LIBRARY_SYNC) }
        assertThat(events).containsExactly(LibrarySyncStepEvent.Completed)
    }

    @Test
    fun `failure does not auto-advance`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncStepViewModel(control, shellStore())
        val events = collectEvents(viewModel)

        control.publish(terminalProgress(LibrarySyncPhase.FAILED))
        runCurrent()

        assertThat(events).isEmpty()
        assertThat(viewModel.state.value.canContinue).isTrue()
    }

    @Test
    fun `a provider-less install does not auto-advance`() = runTest {
        val control = FakeLibrarySyncControl()
        val viewModel = LibrarySyncStepViewModel(control, shellStore())
        val events = collectEvents(viewModel)

        runCurrent()

        assertThat(events).isEmpty()
        assertThat(viewModel.state.value.canContinue).isTrue()
    }

    @Test
    fun `completion followed by a manual continue emits once`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncStepViewModel(control, shellStore())
        val events = collectEvents(viewModel)

        control.publish(terminalProgress(LibrarySyncPhase.DONE))
        runCurrent()
        viewModel.continueIntoApp()
        runCurrent()

        assertThat(events).containsExactly(LibrarySyncStepEvent.Completed)
    }

    @Test
    fun `completion emitted before a collector subscribes is still delivered`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProgress = terminalProgress(LibrarySyncPhase.DONE),
            initialProviders = listOf(provider(id = 1)),
        )
        // Construction runs `init` on the Unconfined main dispatcher, so the
        // completion is emitted before this subscription exists.
        val viewModel = LibrarySyncStepViewModel(control, shellStore())
        val events = collectEvents(viewModel)

        runCurrent()

        assertThat(events).containsExactly(LibrarySyncStepEvent.Completed)
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
        // Subscribed after construction: only the replayed completion is visible,
        // which is exactly what the route sees when it opens late.
        val events = collectEvents(viewModel)

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
        val events = collectEvents(viewModel)
        runCurrent()

        assertThat(control.fullSyncCalls).isEmpty()
        assertThat(viewModel.state.value.isComplete).isTrue()
        assertThat(viewModel.state.value.canContinue).isTrue()
        assertThat(events).containsExactly(LibrarySyncStepEvent.Completed)
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

    private fun TestScope.collectEvents(
        viewModel: LibrarySyncStepViewModel,
    ): MutableList<LibrarySyncStepEvent> {
        val events = mutableListOf<LibrarySyncStepEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }
        return events
    }

    private fun shellStore(): ShellPreferencesStore = mockk(relaxed = true)
}
