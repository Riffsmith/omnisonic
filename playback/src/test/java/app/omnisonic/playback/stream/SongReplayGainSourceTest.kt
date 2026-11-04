package app.omnisonic.playback.stream

import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.playback.api.rg.RgTagType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class SingleRow(private val row: SongEntity?) : SongRowLookup {
    override suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): SongEntity? = row
}

private fun song(
    track: Double? = null,
    album: Double? = null,
    type: Int? = null,
) = SongEntity(
    providerId = 1,
    externalId = "e",
    title = "t",
    replayGainTrack = track,
    replayGainAlbum = album,
    replayGainType = type,
    updatedAt = 0,
)

class SongReplayGainSourceTest {

    @Test
    fun `maps the stored gain columns`() = runTest {
        val values = SongReplayGainSource(SingleRow(song(track = -7.5, album = -9.5, type = 1)))
            .gainFor(1, "e")!!

        assertThat(values.trackGainDb).isEqualTo(-7.5)
        assertThat(values.albumGainDb).isEqualTo(-9.5)
        assertThat(values.tagType).isEqualTo(RgTagType.RG2)
    }

    @Test
    fun `missing row has no values`() = runTest {
        assertThat(SongReplayGainSource(SingleRow(null)).gainFor(1, "e")).isNull()
    }

    @Test
    fun `row without gains yields null dbs and UNKNOWN`() = runTest {
        val values = SongReplayGainSource(SingleRow(song())).gainFor(1, "e")!!

        assertThat(values.trackGainDb).isNull()
        assertThat(values.albumGainDb).isNull()
        assertThat(values.tagType).isEqualTo(RgTagType.UNKNOWN)
    }

    @Test
    fun `absent and out-of-range tag types read as UNKNOWN`() {
        assertThat(null.toRgTagType()).isEqualTo(RgTagType.UNKNOWN)
        assertThat((-1).toRgTagType()).isEqualTo(RgTagType.UNKNOWN)
        assertThat(99.toRgTagType()).isEqualTo(RgTagType.UNKNOWN)
        assertThat(0.toRgTagType()).isEqualTo(RgTagType.RG1)
        assertThat(2.toRgTagType()).isEqualTo(RgTagType.EBU)
    }
}
