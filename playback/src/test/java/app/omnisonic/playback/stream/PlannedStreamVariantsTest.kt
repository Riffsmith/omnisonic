package app.omnisonic.playback.stream

import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private fun variantEntry() = PlaybackQueueEntry(
    providerId = 1,
    externalId = "song-1",
    title = "Queue Title",
    artistName = "Artist",
    coverImageId = "img-1",
    durationMs = 200_000,
    isRadioStation = false,
)

private suspend fun selectorWith(settings: TranscodeSettings): PlannedStreamVariants {
    val store = TranscodeSettingsStore.inMemory()
    store.setSettings(TranscodeRendererId.Local, settings)
    return PlannedStreamVariants(store)
}

class PlannedStreamVariantsTest {

    @Test
    fun `all off desires the auto variant`() = runTest {
        val key = selectorWith(TranscodeSettings()).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:auto:auto")
        Unit
    }

    @Test
    fun `matching stream slots desire the explicit bitrate variant`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 96)

        val key = selectorWith(settings).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:96:auto")
        Unit
    }

    @Test
    fun `a format preference without a bitrate desires the format variant`() = runTest {
        val key = selectorWith(TranscodeSettings(preferredFormat = "mp3")).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:auto:mp3")
        Unit
    }

    @Test
    fun `mandatory alone does not alias the raw auto variant`() = runTest {
        val key = selectorWith(TranscodeSettings(mandatory = true)).desiredKey(variantEntry())

        assertThat(key).isNull()
        Unit
    }

    @Test
    fun `differing stream slots yield no exact preference`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128)

        val key = selectorWith(settings).desiredKey(variantEntry())

        assertThat(key).isNull()
        Unit
    }

    @Test
    fun `a shared format with differing bitrates yields no exact preference`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128, preferredFormat = "mp3")

        val key = selectorWith(settings).desiredKey(variantEntry())

        assertThat(key).isNull()
        Unit
    }

    @Test
    fun `the download slot never leaks into stream variant selection`() = runTest {
        val settings = TranscodeSettings(downloadBitrateKbps = 320)

        val key = selectorWith(settings).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:auto:auto")
        Unit
    }
}
