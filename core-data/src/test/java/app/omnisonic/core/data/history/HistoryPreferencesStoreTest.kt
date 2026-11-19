package app.omnisonic.core.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import com.google.common.truth.Truth.assertThat
import java.io.File
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

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPreferencesStoreTest {

    @TempDir
    lateinit var tempDir: File

    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newDataStore(file: File): DataStore<Preferences> {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = storeScope
        return PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
    }

    private fun newStore(file: File = File(tempDir, "history-${System.nanoTime()}.preferences_pb")) =
        HistoryPreferencesStore(newDataStore(file))

    @Test
    fun `fresh store yields defaults`() = runTest {
        val prefs = newStore().preferences.first()

        assertThat(prefs.playThresholdPercent).isEqualTo(HistoryPreferencesStore.DEFAULT_PLAY_THRESHOLD_PERCENT)
        assertThat(prefs.resumeMinPositionMs).isEqualTo(HistoryPreferencesStore.DEFAULT_RESUME_MIN_POSITION_MS)
        assertThat(prefs.pausedProviderIds).isEmpty()
        assertThat(prefs.albumCountRule).isEqualTo(AlbumCountRule.AVERAGE)
        assertThat(prefs.includeSupportingArtists).isFalse()
    }

    @Test
    fun `every setting round-trips`() = runTest {
        val store = newStore()

        store.setPlayThresholdPercent(50)
        store.setResumeMinPositionMs(30_000L)
        store.setPaused(1L, true)
        store.setPaused(2L, true)
        store.setAlbumCountRule(AlbumCountRule.SUM)
        store.setIncludeSupportingArtists(true)

        val prefs = store.preferences.first()
        assertThat(prefs.playThresholdPercent).isEqualTo(50)
        assertThat(prefs.resumeMinPositionMs).isEqualTo(30_000L)
        assertThat(prefs.pausedProviderIds).containsExactly(1L, 2L)
        assertThat(prefs.albumCountRule).isEqualTo(AlbumCountRule.SUM)
        assertThat(prefs.includeSupportingArtists).isTrue()
    }

    @Test
    fun `pausing one provider keeps the others and unpause removes it`() = runTest {
        val store = newStore()

        store.setPaused(1L, true)
        store.setPaused(2L, true)
        store.setPaused(1L, false)

        assertThat(store.preferences.first().pausedProviderIds).containsExactly(2L)
    }

    @Test
    fun `threshold and resume minimum are clamped into range`() = runTest {
        val store = newStore()

        store.setPlayThresholdPercent(0)
        assertThat(store.preferences.first().playThresholdPercent)
            .isEqualTo(HistoryPreferencesStore.MIN_PLAY_THRESHOLD_PERCENT)

        store.setPlayThresholdPercent(10_000)
        assertThat(store.preferences.first().playThresholdPercent)
            .isEqualTo(HistoryPreferencesStore.MAX_PLAY_THRESHOLD_PERCENT)

        store.setResumeMinPositionMs(-5L)
        assertThat(store.preferences.first().resumeMinPositionMs).isEqualTo(0L)

        store.setResumeMinPositionMs(Long.MAX_VALUE)
        assertThat(store.preferences.first().resumeMinPositionMs)
            .isEqualTo(HistoryPreferencesStore.MAX_RESUME_MIN_POSITION_MS)
    }

    @Test
    fun `an unknown stored rule name decodes to the default`() = runTest {
        val dataStore = newDataStore(File(tempDir, "unknown-rule.preferences_pb"))
        dataStore.edit { it[RULE_KEY] = "MEAN" }

        assertThat(HistoryPreferencesStore(dataStore).preferences.first().albumCountRule)
            .isEqualTo(AlbumCountRule.AVERAGE)
    }

    @Test
    fun `preferences persist across store instances on one file`() = runTest {
        val file = File(tempDir, "shared.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = HistoryPreferencesStore(
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
        )
        first.setPlayThresholdPercent(80)
        first.setResumeMinPositionMs(45_000L)
        first.setPaused(7L, true)
        first.setAlbumCountRule(AlbumCountRule.MINIMUM)
        first.setIncludeSupportingArtists(true)
        firstScope.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }

        val second = newStore(file)
        val prefs = second.preferences.first()

        assertThat(prefs.playThresholdPercent).isEqualTo(80)
        assertThat(prefs.resumeMinPositionMs).isEqualTo(45_000L)
        assertThat(prefs.pausedProviderIds).containsExactly(7L)
        assertThat(prefs.albumCountRule).isEqualTo(AlbumCountRule.MINIMUM)
        assertThat(prefs.includeSupportingArtists).isTrue()
    }

    @Test
    fun `the in-memory store round-trips without a file`() = runTest {
        val store = HistoryPreferencesStore.inMemory()

        store.setPlayThresholdPercent(60)
        store.setPaused(3L, true)

        val prefs = store.current()
        assertThat(prefs.playThresholdPercent).isEqualTo(60)
        assertThat(prefs.pausedProviderIds).containsExactly(3L)
    }

    private companion object {
        val RULE_KEY = stringPreferencesKey("history.album_count_rule")
    }
}
