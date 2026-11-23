package app.omnisonic.core.model.style

/**
 * Application-wide appearance. Every enum constant is the persisted value
 * token: the decoder maps the token by name and rejects anything else (D-64).
 */
data class StyleApp(
    val colorSource: ColorSource = ColorSource.SYSTEM,
    val seedColor: String = DEFAULT_SEED_COLOR,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val colorPalette: ColorPalette = ColorPalette.TONAL_SPOT,
    val colorContrast: ColorContrast = ColorContrast.STANDARD,
    val cornerScale: CornerRoundness = CornerRoundness.DEFAULT,
    val fontFamilies: StyleFontFamilies = StyleFontFamilies(),
    val effects: StyleEffects = StyleEffects(),
) {
    companion object {
        /** `#RRGGBB` or `#AARRGGBB`; the default equals the Phase 5 primary seed. */
        const val DEFAULT_SEED_COLOR: String = "#7CC4FF"
    }
}

enum class ColorSource { SYSTEM, SEED }

enum class DarkMode { SYSTEM, LIGHT, DARK, BLACK }

enum class ColorPalette {
    TONAL_SPOT,
    NEUTRAL,
    VIBRANT,
    EXPRESSIVE,
    RAINBOW,
    FRUIT_SALAD,
    MONOCHROME,
    FIDELITY,
    CONTENT,
}

enum class ColorContrast { STANDARD, MEDIUM, HIGH }

enum class CornerRoundness { DEFAULT, MEDIUM, LARGE, EXTRA_LARGE }

/** A `null` family renders the platform default, matching `FontFamilies` in `:style`. */
data class StyleFontFamilies(
    val title: String? = null,
    val body: String? = null,
    val lyrics: String? = null,
    val nav: String? = null,
)

data class StyleEffects(
    val quality: QualityTier = QualityTier.AUTO,
    val progressiveBlur: ProgressiveBlur = ProgressiveBlur.AUTO,
    val overlayAlpha: Float = 0.4f,
    val legibilityShield: Boolean = true,
    val artColorFollowing: Boolean = false,
    val audioReactive: Boolean = false,
    val tiltParallax: Boolean = false,
    val tiltParallaxMaxOffsetDp: Float = 12f,
)

enum class QualityTier { AUTO, LOW, HIGH }

enum class ProgressiveBlur { AUTO, ON, OFF }
