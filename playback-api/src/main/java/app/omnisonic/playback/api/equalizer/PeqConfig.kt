package app.omnisonic.playback.api.equalizer

enum class PeqBandType { LOW_PASS, HIGH_PASS, BAND_PASS, LOW_SHELF, HIGH_SHELF, PEAKING }

data class PeqBand(
    val type: PeqBandType,
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    /** Stored for a per-band later UI; both channels are equalised now. */
    val soloChannel: Int? = null,
)

data class PeqConfig(
    val preampDb: Float = 0f,
    val bands: List<PeqBand> = emptyList(),
) {
    fun isEmpty(): Boolean = bands.isEmpty() && preampDb == 0f
}

object PeqDefaults {
    val KNEE_NORMAL = 1.0f
    val KNEE_HARD = 0.1f
}
