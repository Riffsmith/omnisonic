package app.omnisonic.playback.api.rg

/** Mode picked by the user (see phase plan §4 sink processors). */
enum class ReplayGainMode { OFF, TRACK, PREFER_TRACK, ALBUM, PREFER_ALBUM, AUTOMATIC }

/**
 * Reference-level application type. RG2 applies the stored dB offset as-is;
 * EBU falls back to the stored loudness-relative offset when a loudness scan
 * exists, and otherwise behaves as RG2 (documented gap until scanning lands).
 */
enum class RgNormalisation { OFF, RG2, EBU }

/** Values as stored per song row (`replay_gain_track`, `replay_gain_album`, `replay_gain_type`). */
data class ReplayGainValues(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val tagType: RgTagType = RgTagType.UNKNOWN,
)

enum class RgTagType { RG1, RG2, EBU, UNKNOWN }

/**
 * Resolves the dB offset to apply for one track. Order semantics:
 * OFF → 0; TRACK → track only (skip normalisation when absent);
 * PREFER_TRACK → track, else album; ALBUM → album only (skip when absent);
 * PREFER_ALBUM → album, else track; AUTOMATIC → album, else track.
 */
object ReplayGainSelector {
    const val DEFAULT_TARGET_LOUDNESS_LUFS = -18.0

    fun gainDb(
        values: ReplayGainValues?,
        mode: ReplayGainMode,
        normalisation: RgNormalisation,
        targetLoudnessLufs: Double = DEFAULT_TARGET_LOUDNESS_LUFS,
        measuredLoudnessLufs: Double? = null,
    ): Double {
        if (values == null || mode == ReplayGainMode.OFF) return 0.0
        val base = when (mode) {
            ReplayGainMode.TRACK -> values.trackGainDb ?: return 0.0
            ReplayGainMode.PREFER_TRACK -> values.trackGainDb ?: values.albumGainDb ?: return 0.0
            ReplayGainMode.ALBUM -> values.albumGainDb ?: return 0.0
            ReplayGainMode.PREFER_ALBUM -> values.albumGainDb ?: values.trackGainDb ?: return 0.0
            ReplayGainMode.AUTOMATIC -> values.albumGainDb ?: values.trackGainDb
            ReplayGainMode.OFF -> return 0.0
        } ?: return 0.0
        if (normalisation == RgNormalisation.OFF) return 0.0
        if (normalisation == RgNormalisation.EBU && measuredLoudnessLufs != null) {
            return targetLoudnessLufs - measuredLoudnessLufs
        }
        return base
    }

    /** dB offset → linear multiplier (`10^(dB/20)`). */
    fun linearMultiplier(gainDb: Double): Float = Math.pow(10.0, gainDb / 20.0).toFloat()
}
