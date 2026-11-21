package app.omnisonic.feature.settings.history

import android.content.Context
import android.net.Uri
import app.omnisonic.core.data.backup.BackupDocumentSink
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.dao.ProviderDao
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.export.PlayEventCsvExporter
import app.omnisonic.core.data.history.HistoryPreferencesStore
import app.omnisonic.core.data.history.HistoryRecordingGate
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySettingsViewModelTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newStore(tag: String) = HistoryPreferencesStore.createAt(
        File(tempDir, "$tag-${System.nanoTime()}.preferences_pb"),
    )

    private fun viewModel(
        store: HistoryPreferencesStore,
        gate: HistoryRecordingGate = HistoryRecordingGate(store),
        aggregateSettings: PlayAggregateSettings = PlayAggregateSettings(store),
        providers: List<ProviderIdentityRow> = listOf(ProviderIdentityRow(1L, "Home server")),
        exporter: PlayEventCsvExporter = mockk(),
    ): HistorySettingsViewModel {
        val providerDao = mockk<ProviderDao>()
        every { providerDao.observeIdentities() } returns flowOf(providers)
        val db = mockk<OmnisonicDatabase>()
        every { db.providerDao() } returns providerDao
        val context = mockk<Context>()
        every { context.contentResolver } returns mockk()
        return HistorySettingsViewModel(store, gate, aggregateSettings, db, exporter, context)
    }

    @Test
    fun `defaults render with the provider list`(): Unit = runBlocking {
        val viewModel = viewModel(newStore("defaults"))

        withTimeoutOrNull(2_000) { while (viewModel.state.value.loading) delay(10) }
        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.playThresholdPercent).isEqualTo(95)
        assertThat(state.resumeMinPositionMs).isEqualTo(10_000L)
        assertThat(state.pausedProviderIds).isEmpty()
        assertThat(state.providers.map { it.name }).containsExactly("Home server")
        assertThat(state.albumCountRule).isEqualTo(AlbumCountRule.AVERAGE)
        assertThat(state.includeSupportingArtists).isFalse()
        assertThat(viewModel.exportState.value).isEqualTo(HistoryCsvExportState.Idle)
    }

    @Test
    fun `threshold and resume minimum write through to the store and clamp`(): Unit = runBlocking {
        val store = newStore("threshold")
        val viewModel = viewModel(store)
        withTimeoutOrNull(2_000) { while (viewModel.state.value.loading) delay(10) }

        viewModel.setPlayThresholdPercent(50)
        viewModel.setResumeMinPositionMs(30_000L)
        // Both setters are independent writes; waiting on only one can read the other mid-flight.
        withTimeoutOrNull(2_000) {
            while (store.current().let { it.playThresholdPercent != 50 || it.resumeMinPositionMs != 30_000L }) {
                delay(10)
            }
        }
        assertThat(store.current().playThresholdPercent).isEqualTo(50)
        assertThat(store.current().resumeMinPositionMs).isEqualTo(30_000L)
        withTimeoutOrNull(2_000) {
            while (viewModel.state.value.let { it.playThresholdPercent != 50 || it.resumeMinPositionMs != 30_000L }) {
                delay(10)
            }
        }
        assertThat(viewModel.state.value.playThresholdPercent).isEqualTo(50)
        assertThat(viewModel.state.value.resumeMinPositionMs).isEqualTo(30_000L)

        viewModel.setPlayThresholdPercent(0)
        viewModel.setResumeMinPositionMs(-1L)
        withTimeoutOrNull(2_000) {
            while (store.current().let { it.playThresholdPercent != 1 || it.resumeMinPositionMs != 0L }) {
                delay(10)
            }
        }
        assertThat(store.current().playThresholdPercent).isEqualTo(1)
        assertThat(store.current().resumeMinPositionMs).isEqualTo(0L)
    }

    @Test
    fun `the provider pause toggle goes through the gate and persists`(): Unit = runBlocking {
        val store = newStore("pause")
        val gate = HistoryRecordingGate(store)
        val viewModel = viewModel(store, gate)
        withTimeoutOrNull(2_000) { while (viewModel.state.value.loading) delay(10) }

        viewModel.setProviderRecordingPaused(1L, true)

        assertThat(gate.isPaused(1L)).isTrue()
        withTimeoutOrNull(2_000) {
            while (store.current().pausedProviderIds != setOf(1L)) delay(10)
        }
        assertThat(store.current().pausedProviderIds).containsExactly(1L)
        withTimeoutOrNull(2_000) {
            while (1L !in viewModel.state.value.pausedProviderIds) delay(10)
        }
        assertThat(viewModel.state.value.pausedProviderIds).containsExactly(1L)
    }

    @Test
    fun `aggregate options write through to the store`(): Unit = runBlocking {
        val store = newStore("aggregates")
        val settings = PlayAggregateSettings(store)
        val viewModel = viewModel(store, aggregateSettings = settings)
        withTimeoutOrNull(2_000) { while (viewModel.state.value.loading) delay(10) }

        viewModel.setAlbumCountRule(AlbumCountRule.MINIMUM)
        viewModel.setIncludeSupportingArtists(true)

        withTimeoutOrNull(2_000) {
            while (settings.options().albumCountRule != AlbumCountRule.MINIMUM ||
                !settings.options().includeSupportingArtists) delay(10)
        }
        assertThat(settings.options().albumCountRule).isEqualTo(AlbumCountRule.MINIMUM)
        assertThat(settings.options().includeSupportingArtists).isTrue()

        withTimeoutOrNull(2_000) {
            while (store.current().albumCountRule != AlbumCountRule.MINIMUM ||
                !store.current().includeSupportingArtists) delay(10)
        }
        assertThat(store.current().albumCountRule).isEqualTo(AlbumCountRule.MINIMUM)
        assertThat(store.current().includeSupportingArtists).isTrue()
        withTimeoutOrNull(2_000) {
            while (viewModel.state.value.albumCountRule != AlbumCountRule.MINIMUM) delay(10)
        }
        assertThat(viewModel.state.value.albumCountRule).isEqualTo(AlbumCountRule.MINIMUM)
        assertThat(viewModel.state.value.includeSupportingArtists).isTrue()
    }

    @Test
    fun `export csv reports the exported event count`(): Unit = runBlocking {
        val exporter = mockk<PlayEventCsvExporter>()
        coEvery { exporter.writeCsv(any<BackupDocumentSink>(), any()) } returns 7
        val viewModel = viewModel(newStore("export"), exporter = exporter)

        viewModel.exportCsv(mockk<Uri>())

        withTimeoutOrNull(2_000) {
            while (viewModel.exportState.value !is HistoryCsvExportState.Done) delay(10)
        }
        assertThat(viewModel.exportState.value).isEqualTo(HistoryCsvExportState.Done(7))
    }

    @Test
    fun `a failed export reports the error`(): Unit = runBlocking {
        val exporter = mockk<PlayEventCsvExporter>()
        coEvery { exporter.writeCsv(any<BackupDocumentSink>(), any()) } throws IOException("disk full")
        val viewModel = viewModel(newStore("export-fail"), exporter = exporter)

        viewModel.exportCsv(mockk<Uri>())

        withTimeoutOrNull(2_000) {
            while (viewModel.exportState.value !is HistoryCsvExportState.Failed) delay(10)
        }
        val failure = viewModel.exportState.value as HistoryCsvExportState.Failed
        assertThat(failure.message).contains("disk full")
    }
}
