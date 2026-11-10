package app.omnisonic.feature.settings.sync

import app.omnisonic.core.data.sync.LibrarySyncLastResult
import app.omnisonic.core.data.sync.LibrarySyncPhase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySyncManagerViewModelTest {

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
    fun `providers render with their live walk`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProgress = runningProgress(providerId = 1),
            initialProviders = listOf(
                provider(id = 1, name = "Home"),
                provider(id = 2, name = "On device", supportsLibrarySync = false),
            ),
        )

        val viewModel = LibrarySyncManagerViewModel(control)

        val entries = viewModel.state.value.entries
        assertThat(entries).hasSize(1)
        val home = entries.single()
        assertThat(home.provider.name).isEqualTo("Home")
        assertThat(home.isRunning).isTrue()
        assertThat(home.live?.phase).isEqualTo(LibrarySyncPhase.ALBUMS_SONGS)
        assertThat(viewModel.state.value.loading).isFalse()
    }

    @Test
    fun `sync now starts a diff walk and refuses a second while running`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncManagerViewModel(control)

        viewModel.syncNow(1)
        assertThat(control.diffSyncCalls).containsExactly(1L)

        control.publish(runningProgress(providerId = 1))
        runCurrent()
        viewModel.syncNow(1)

        assertThat(control.diffSyncCalls).containsExactly(1L)
    }

    @Test
    fun `full resync asks for confirmation before starting`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncManagerViewModel(control)

        viewModel.requestFullResync(1)
        assertThat(viewModel.state.value.confirmingProviderId).isEqualTo(1)

        viewModel.dismissFullResync()
        assertThat(viewModel.state.value.confirmingProviderId).isNull()
        assertThat(control.fullSyncCalls).isEmpty()

        viewModel.requestFullResync(1)
        viewModel.confirmFullResync()
        assertThat(viewModel.state.value.confirmingProviderId).isNull()
        assertThat(control.fullSyncCalls).containsExactly(1L)
    }

    @Test
    fun `refresh picks up a provider that existed only after launch`() = runTest {
        val control = FakeLibrarySyncControl()
        val viewModel = LibrarySyncManagerViewModel(control)
        assertThat(viewModel.state.value.entries).isEmpty()

        control.providersResult = listOf(provider(id = 1))
        viewModel.refresh()
        runCurrent()

        assertThat(viewModel.state.value.entries.single().provider.name).isEqualTo("Home")
    }

    @Test
    fun `a terminal walk refreshes the recorded result`() = runTest {
        val control = FakeLibrarySyncControl(
            initialProviders = listOf(provider(id = 1)),
        )
        val viewModel = LibrarySyncManagerViewModel(control)
        val requestsBefore = control.providersRequests

        control.publish(runningProgress(providerId = 1))
        runCurrent()
        assertThat(viewModel.state.value.entries.single().isRunning).isTrue()
        assertThat(control.providersRequests).isEqualTo(requestsBefore)

        control.providersResult = listOf(
            provider(id = 1, lastFullSyncAt = 5_000, lastResult = LibrarySyncLastResult.COMPLETED),
        )
        control.publish(terminalProgress(LibrarySyncPhase.DONE, providerId = 1))
        runCurrent()

        val entry = viewModel.state.value.entries.single()
        assertThat(entry.isRunning).isFalse()
        assertThat(entry.provider.lastResult).isEqualTo(LibrarySyncLastResult.COMPLETED)
        assertThat(control.providersRequests).isGreaterThan(requestsBefore)
    }

    private fun runCurrent() {
        mainDispatcher.scheduler.runCurrent()
    }
}
