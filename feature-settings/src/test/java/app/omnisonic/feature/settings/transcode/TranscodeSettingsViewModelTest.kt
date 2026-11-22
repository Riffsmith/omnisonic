package app.omnisonic.feature.settings.transcode

import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscodeSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: TranscodeSettingsStore
    private lateinit var viewModel: TranscodeSettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = TranscodeSettingsStore.inMemory()
        viewModel = TranscodeSettingsViewModel(store)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `absent keys default both toggles to true`() = runTest(dispatcher) {
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.vpnAsCellular).isTrue()
        assertThat(state.meteredAsCellular).isTrue()
    }

    @Test
    fun `persisted values build the initial state`() = runTest(dispatcher) {
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(vpnAsCellular = false, meteredAsCellular = false),
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.vpnAsCellular).isFalse()
        assertThat(viewModel.state.value.meteredAsCellular).isFalse()
    }

    @Test
    fun `toggling vpn writes the whole object and the row state matches the store`() = runTest(dispatcher) {
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        advanceUntilIdle()

        viewModel.setVpnAsCellular(false)
        advanceUntilIdle()

        val persisted = store.current(TranscodeRendererId.Local)
        assertThat(persisted.vpnAsCellular).isFalse()
        // Whole-object write: the other fields survive the toggle.
        assertThat(persisted.wifiBitrateKbps).isEqualTo(320)
        assertThat(persisted.cellularBitrateKbps).isEqualTo(96)
        assertThat(persisted.meteredAsCellular).isTrue()
        assertThat(viewModel.state.value.vpnAsCellular).isFalse()
        assertThat(viewModel.state.value.meteredAsCellular).isTrue()
    }

    @Test
    fun `toggling metered writes the whole object without touching vpn`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.setMeteredAsCellular(false)
        advanceUntilIdle()

        val persisted = store.current(TranscodeRendererId.Local)
        assertThat(persisted.meteredAsCellular).isFalse()
        assertThat(persisted.vpnAsCellular).isTrue()
        assertThat(viewModel.state.value).isEqualTo(
            TranscodeSettingsUiState(loading = false, vpnAsCellular = true, meteredAsCellular = false),
        )
    }

    @Test
    fun `rows are independent of other renderers`() = runTest(dispatcher) {
        val cast = TranscodeRendererId("cast")
        store.setSettings(cast, TranscodeSettings(vpnAsCellular = false, meteredAsCellular = false))
        advanceUntilIdle()

        viewModel.setVpnAsCellular(false)
        advanceUntilIdle()

        assertThat(store.current(TranscodeRendererId.Local).vpnAsCellular).isFalse()
        assertThat(store.current(cast).vpnAsCellular).isFalse()
        assertThat(store.current(cast).meteredAsCellular).isFalse()
        assertThat(store.current(TranscodeRendererId.Local).meteredAsCellular).isTrue()
    }

    @Test
    fun `rapid interleaved toggles lose no update`() = runTest(dispatcher) {
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        advanceUntilIdle()

        val jobs = (1..10).map { i ->
            async {
                if (i % 2 == 0) viewModel.setVpnAsCellular(i % 4 == 0) else viewModel.setMeteredAsCellular(i % 4 == 0)
            }
        }
        jobs.awaitAll()
        advanceUntilIdle()

        val persisted = store.current(TranscodeRendererId.Local)
        // Every write was a whole-object read-modify-write, so no toggle clobbered the other field.
        assertThat(persisted.wifiBitrateKbps).isEqualTo(320)
        assertThat(persisted.cellularBitrateKbps).isEqualTo(96)
        // The state mirrors the store exactly after the race settles.
        assertThat(viewModel.state.value.vpnAsCellular).isEqualTo(persisted.vpnAsCellular)
        assertThat(viewModel.state.value.meteredAsCellular).isEqualTo(persisted.meteredAsCellular)
    }
}
