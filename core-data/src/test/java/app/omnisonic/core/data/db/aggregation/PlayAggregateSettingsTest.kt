package app.omnisonic.core.data.db.aggregation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.omnisonic.core.data.history.BlockingHistoryDataStore
import app.omnisonic.core.data.history.HistoryPreferencesStore
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class PlayAggregateSettingsTest {

    @TempDir
    lateinit var tempDir: File

    private fun newStore(tag: String) = HistoryPreferencesStore.createAt(
        File(tempDir, "$tag-${System.nanoTime()}.preferences_pb"),
    )

    @Test
    fun `defaults are average rule and supporting credits off`() = runTest {
        val settings = PlayAggregateSettings()

        assertThat(settings.options().albumCountRule).isEqualTo(AlbumCountRule.AVERAGE)
        assertThat(settings.options().includeSupportingArtists).isFalse()
    }

    @Test
    fun `update persists the options to the store`(): Unit = runBlocking {
        val store = newStore("update")
        val settings = PlayAggregateSettings(store)

        settings.update(
            PlayAggregateSettings.Options(
                albumCountRule = AlbumCountRule.SUM,
                includeSupportingArtists = true,
            ),
        )

        assertThat(settings.options().albumCountRule).isEqualTo(AlbumCountRule.SUM)
        withTimeoutOrNull(2_000) {
            while (store.current().albumCountRule != AlbumCountRule.SUM) delay(10)
        }
        val prefs = store.current()
        assertThat(prefs.albumCountRule).isEqualTo(AlbumCountRule.SUM)
        assertThat(prefs.includeSupportingArtists).isTrue()
    }

    @Test
    fun `a fresh holder is seeded from the persisted options`() = runTest {
        val file = seedFile("seed") { store ->
            store.setAggregateOptions(AlbumCountRule.MINIMUM, includeSupportingArtists = true)
            withTimeoutOrNull(2_000) {
                while (store.current().albumCountRule != AlbumCountRule.MINIMUM) delay(10)
            }
        }

        val settings = PlayAggregateSettings(HistoryPreferencesStore.createAt(file))
        withTimeoutOrNull(2_000) {
            while (settings.options().albumCountRule != AlbumCountRule.MINIMUM) delay(10)
        }

        assertThat(settings.options().albumCountRule).isEqualTo(AlbumCountRule.MINIMUM)
        assertThat(settings.options().includeSupportingArtists).isTrue()
        assertThat(settings.optionsFlow().first().albumCountRule).isEqualTo(AlbumCountRule.MINIMUM)
    }

    @Test
    fun `an update made while the seed is blocked is not overwritten`(): Unit = runBlocking {
        val dataStore = BlockingHistoryDataStore()
        val settings = PlayAggregateSettings(HistoryPreferencesStore(dataStore))
        withTimeout(2_000) { dataStore.readStarted.await() }
        val updated = PlayAggregateSettings.Options(
            albumCountRule = AlbumCountRule.MINIMUM,
            includeSupportingArtists = true,
        )

        settings.update(updated)
        dataStore.releaseRead.complete(Unit)
        withTimeout(2_000) { settings.awaitReady() }

        assertThat(settings.options()).isEqualTo(updated)
    }

    /** Writes through a store instance that is closed (and flushed) before the file is reopened. */
    private suspend fun seedFile(tag: String, block: suspend (HistoryPreferencesStore) -> Unit): File {
        val file = File(tempDir, "$tag-${System.nanoTime()}.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = HistoryPreferencesStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
        block(store)
        scope.cancel()
        withTimeoutOrNull(2_000) { while (job.children.any()) delay(10) }
        return file
    }
}
