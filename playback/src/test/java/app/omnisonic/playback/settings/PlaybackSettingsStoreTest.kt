package app.omnisonic.playback.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import app.omnisonic.playback.api.player.OffloadMode
import app.omnisonic.playback.api.rg.ReplayGainMode
import app.omnisonic.playback.api.rg.RgNormalisation
import app.omnisonic.playback.api.rg.ReplayGainSelector
import app.omnisonic.playback.api.settings.DefaultNotificationActions
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.settings.TransientLossAction
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSettingsStoreTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var scope: CoroutineScope

    private suspend fun newStore(existingData: Boolean = false): PlaybackSettingsStore {
        scope = CoroutineScope(SupervisorJob())
        val file = File(tempDir, if (existingData) "existing.preferences_pb" else "fresh-${System.nanoTime()}.preferences_pb")
        if (existingData) {
            val writerJob = SupervisorJob()
            val writerScope = CoroutineScope(writerJob + Dispatchers.IO)
            val writer = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
            writer.edit { it[PlaybackPrefs.OFFLOAD] = OffloadMode.ENABLED.ordinal }
            writerJob.cancel()
            withTimeoutOrNull(2_000) {
                while (writerJob.children.toList().isNotEmpty()) delay(10)
            }
            check(writerJob.isCompleted) { "writer scope never completed" }
            delay(50)
        }
        return PlaybackSettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
    }

    @Test
    fun `fresh store yields defaults`() = runTest {
        val store = newStore()
        assertThat(store.offloadMode.first()).isEqualTo(OffloadMode.DISABLED)
        assertThat(store.replayGainMode.first()).isEqualTo(ReplayGainMode.OFF)
        assertThat(store.replayGainNormalisation.first()).isEqualTo(RgNormalisation.RG2)
        assertThat(store.replayGainTargetLoudnessLufs.first()).isEqualTo(ReplayGainSelector.DEFAULT_TARGET_LOUDNESS_LUFS)
        assertThat(store.mono.first()).isFalse()
        assertThat(store.cacheFirst.first()).isFalse()
        assertThat(store.cacheSizeBytes.first()).isEqualTo(PlaybackSettingsStore.DEFAULT_CACHE_SIZE_BYTES)
        assertThat(store.transientLossAction.first()).isEqualTo(TransientLossAction.DUCK)
        assertThat(store.notificationActions.first()).isEqualTo(
            DefaultNotificationActions.SLOT_DEFAULTS,
        )
        assertThat(store.shortFadeMs.first()).isEqualTo(PlaybackSettingsStore.DEFAULT_SHORT_FADE_MS)
        scope.cancel()
    }
    @Test
    fun `writes survive roundtrip`() = runTest {
        val store = newStore()
        store.setOffloadMode(OffloadMode.AUTO)
        store.setReplayGainMode(ReplayGainMode.PREFER_ALBUM)
        store.setReplayGainNormalisation(RgNormalisation.OFF)
        store.setReplayGainTargetLoudnessLufs(-19.5)
        store.setMono(true)
        store.setCacheFirst(true)
        store.setCacheSizeBytes(1024)
        store.setTransientLossAction(TransientLossAction.PAUSE)
        store.setShortFadeMs(80)
        store.setNotificationActions(listOf(NotificationAction.SEEK_BACK_10, NotificationAction.NONE, NotificationAction.NONE, NotificationAction.NONE, NotificationAction.NONE))

        assertThat(store.offloadMode.first()).isEqualTo(OffloadMode.AUTO)
        assertThat(store.replayGainMode.first()).isEqualTo(ReplayGainMode.PREFER_ALBUM)
        assertThat(store.replayGainNormalisation.first()).isEqualTo(RgNormalisation.OFF)
        assertThat(store.replayGainTargetLoudnessLufs.first()).isEqualTo(-19.5)
        assertThat(store.mono.first()).isTrue()
        assertThat(store.cacheFirst.first()).isTrue()
        assertThat(store.cacheSizeBytes.first()).isEqualTo(1024)
        assertThat(store.transientLossAction.first()).isEqualTo(TransientLossAction.PAUSE)
        assertThat(store.shortFadeMs.first()).isEqualTo(80)
        assertThat(store.notificationActions.first().first()).isEqualTo(NotificationAction.SEEK_BACK_10)
        scope.cancel()
    }

    @Test
    fun `persisted store keeps earlier values`() = runTest {
        val store = newStore(existingData = true)
        assertThat(store.offloadMode.first()).isEqualTo(OffloadMode.ENABLED)
        scope.cancel()
    }

    @Test
    fun `enum settings persist by name instead of ordinal`() = runTest {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tempDir, "names-${System.nanoTime()}.preferences_pb") },
        )
        val store = PlaybackSettingsStore(dataStore)

        store.setOffloadMode(OffloadMode.AUTO)
        store.setReplayGainMode(ReplayGainMode.PREFER_TRACK)
        store.setReplayGainNormalisation(RgNormalisation.EBU)
        store.setTransientLossAction(TransientLossAction.PAUSE)
        val prefs = dataStore.data.first()

        assertThat(prefs[PlaybackPrefs.OFFLOAD_NAME]).isEqualTo("AUTO")
        assertThat(prefs[PlaybackPrefs.RG_MODE_NAME]).isEqualTo("PREFER_TRACK")
        assertThat(prefs[PlaybackPrefs.RG_NORM_NAME]).isEqualTo("EBU")
        assertThat(prefs[PlaybackPrefs.TRANSIENT_LOSS_NAME]).isEqualTo("PAUSE")
        assertThat(prefs[PlaybackPrefs.OFFLOAD]).isNull()
        assertThat(prefs[PlaybackPrefs.RG_MODE]).isNull()
        assertThat(prefs[PlaybackPrefs.RG_NORM]).isNull()
        assertThat(prefs[PlaybackPrefs.TRANSIENT_LOSS]).isNull()
        assertThat(store.offloadMode.first()).isEqualTo(OffloadMode.AUTO)
        assertThat(store.replayGainMode.first()).isEqualTo(ReplayGainMode.PREFER_TRACK)
        assertThat(store.replayGainNormalisation.first()).isEqualTo(RgNormalisation.EBU)
        assertThat(store.transientLossAction.first()).isEqualTo(TransientLossAction.PAUSE)
        storeScope.cancel()
    }

    @Test
    fun `an unknown stored name falls back to the default`() = runTest {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tempDir, "unknown-${System.nanoTime()}.preferences_pb") },
        )
        dataStore.edit { prefs ->
            prefs[PlaybackPrefs.OFFLOAD_NAME] = "NOT_A_MODE"
            prefs[PlaybackPrefs.RG_MODE_NAME] = "NOT_A_MODE"
            prefs[PlaybackPrefs.RG_NORM_NAME] = "NOT_A_MODE"
            prefs[PlaybackPrefs.TRANSIENT_LOSS_NAME] = "NOT_A_MODE"
        }
        val store = PlaybackSettingsStore(dataStore)

        assertThat(store.offloadMode.first()).isEqualTo(OffloadMode.DISABLED)
        assertThat(store.replayGainMode.first()).isEqualTo(ReplayGainMode.OFF)
        assertThat(store.replayGainNormalisation.first()).isEqualTo(RgNormalisation.RG2)
        assertThat(store.transientLossAction.first()).isEqualTo(TransientLossAction.DUCK)
        storeScope.cancel()
    }

    @Test
    fun `legacy ordinal values are mapped for one release and survive a rewrite`() = runTest {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tempDir, "legacy-mapped-${System.nanoTime()}.preferences_pb") },
        )
        dataStore.edit { prefs -> prefs[PlaybackPrefs.OFFLOAD] = OffloadMode.ENABLED.ordinal }
        val store = PlaybackSettingsStore(dataStore)

        assertThat(store.offloadMode.first()).isEqualTo(OffloadMode.ENABLED)

        store.setOffloadMode(OffloadMode.ENABLED)
        val prefs = dataStore.data.first()
        assertThat(prefs[PlaybackPrefs.OFFLOAD_NAME]).isEqualTo("ENABLED")
        assertThat(prefs[PlaybackPrefs.OFFLOAD]).isNull()
        storeScope.cancel()
    }

    @Test
    fun `writes clear the legacy ordinal keys`() = runTest {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tempDir, "names-${System.nanoTime()}.preferences_pb") },
        )
        val store = PlaybackSettingsStore(dataStore)

        store.setOffloadMode(OffloadMode.AUTO)
        store.setReplayGainMode(ReplayGainMode.PREFER_TRACK)
        store.setReplayGainNormalisation(RgNormalisation.EBU)
        store.setTransientLossAction(TransientLossAction.PAUSE)
        val prefs = dataStore.data.first()

        assertThat(prefs[PlaybackPrefs.OFFLOAD]).isNull()
        assertThat(prefs[PlaybackPrefs.RG_MODE]).isNull()
        assertThat(prefs[PlaybackPrefs.RG_NORM]).isNull()
        assertThat(prefs[PlaybackPrefs.TRANSIENT_LOSS]).isNull()
        storeScope.cancel()
    }

    @Test
    fun `a legacy ordinal outside the enum range falls back to the default`() = runTest {
        val file = File(tempDir, "out-of-range-${System.nanoTime()}.preferences_pb")
        val writerJob = SupervisorJob()
        val writerScope = CoroutineScope(writerJob + Dispatchers.IO)
        val writer = PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
        writer.edit { prefs ->
            prefs[PlaybackPrefs.OFFLOAD] = 99
            prefs[PlaybackPrefs.TRANSIENT_LOSS] = -1
        }
        writerJob.cancel()
        withTimeoutOrNull(2_000) {
            while (writerJob.children.toList().isNotEmpty()) delay(10)
        }
        check(writerJob.isCompleted) { "writer scope never completed" }
        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PlaybackSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = readerScope,
                produceFile = { file },
            ),
        )

        assertThat(store.offloadMode.first()).isEqualTo(OffloadMode.DISABLED)
        assertThat(store.transientLossAction.first()).isEqualTo(TransientLossAction.DUCK)
        readerScope.cancel()
    }
}
