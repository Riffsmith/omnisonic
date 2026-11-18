package app.omnisonic.feature.settings.sync

import app.omnisonic.core.data.sync.OutboxPendingProvider
import app.omnisonic.core.data.sync.OutboxPendingRepository
import app.omnisonic.core.data.sync.OutboxSyncState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingSyncViewModelTest {

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
    fun `pending rows render with a total and the newest error`() = runTest {
        val viewModel = PendingSyncViewModel(
            pendingRepository(
                OutboxPendingProvider(1, "Home", pendingCount = 3, failingCount = 1, lastError = "server said no"),
                OutboxPendingProvider(2, "On device", pendingCount = 2, failingCount = 0, lastError = null),
            ),
            FakeOutboxSyncControl(),
        )

        val state = viewModel.state.value

        assertThat(state.loading).isFalse()
        assertThat(state.totalPending).isEqualTo(5)
        assertThat(state.providers.map { it.providerId }).containsExactly(1L, 2L).inOrder()
        assertThat(state.providers[0].lastError).isEqualTo("server said no")
        assertThat(state.providers[1].failingCount).isEqualTo(0)
        assertThat(state.draining).isFalse()
    }

    @Test
    fun `an empty outbox renders zero pending`() = runTest {
        val viewModel = PendingSyncViewModel(pendingRepository(), FakeOutboxSyncControl())

        assertThat(viewModel.state.value.totalPending).isEqualTo(0)
        assertThat(viewModel.state.value.providers).isEmpty()
        assertThat(viewModel.state.value.loading).isFalse()
    }

    @Test
    fun `a running drain marks the state as draining`() = runTest {
        val control = FakeOutboxSyncControl()
        val viewModel = PendingSyncViewModel(
            pendingRepository(OutboxPendingProvider(1, "Home", 1, 0, null)),
            control,
        )

        control.publish(OutboxSyncState(running = true, lastRunAt = 1_000))

        assertThat(viewModel.state.value.draining).isTrue()
    }

    @Test
    fun `retry asks the coordinator to clear the back-off and drain`() = runTest {
        val control = FakeOutboxSyncControl()
        val viewModel = PendingSyncViewModel(pendingRepository(), control)

        viewModel.retryNow()

        assertThat(control.retryCalls).isEqualTo(1)
    }

    private fun pendingRepository(
        vararg providers: OutboxPendingProvider,
    ): OutboxPendingRepository {
        val repository = mockk<OutboxPendingRepository>()
        every { repository.providers } returns flowOf(providers.toList())
        return repository
    }
}
