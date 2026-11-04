package app.omnisonic.playback.stream

import app.omnisonic.playback.api.rg.ReplayGainSource
import app.omnisonic.playback.api.rg.ReplayGainValues
import app.omnisonic.playback.api.rg.RgTagType
import javax.inject.Inject

/**
 * Song-row-backed [ReplayGainSource] — the first cut of the RG source order
 * (tag values scanned into the local library; server-provided values unused).
 * The tag scanner that populates the columns lands with the local library work.
 */
class SongReplayGainSource @Inject constructor(
    private val songs: SongRowLookup,
) : ReplayGainSource {

    override suspend fun gainFor(providerId: Long, externalId: String): ReplayGainValues? {
        val song = songs.selectByProviderAndExternal(providerId, externalId) ?: return null
        return ReplayGainValues(
            trackGainDb = song.replayGainTrack,
            albumGainDb = song.replayGainAlbum,
            tagType = song.replayGainType.toRgTagType(),
        )
    }
}

/**
 * `songs.replayGainType` stores [RgTagType.ordinal]; null and out-of-range
 * values read as UNKNOWN until the tag scanner writes the column.
 */
internal fun Int?.toRgTagType(): RgTagType =
    this?.let { RgTagType.entries.getOrNull(it) } ?: RgTagType.UNKNOWN
