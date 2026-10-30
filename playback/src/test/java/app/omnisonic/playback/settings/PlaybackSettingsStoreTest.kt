package app.omnisonic.playback.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.omnisonic.playback.api.player.DecoderPreference
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
        assertThat(store.decoderPreference.first()).isEqualTo(DecoderPreference.MEDIA_FIRST)
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
        store.setDecoderPreference(DecoderPreference.EXTENSION_FIRST)
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

        assertThat(store.decoderPreference.first()).isEqualTo(DecoderPreference.EXTENSION_FIRST)
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
}
