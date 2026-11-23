package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class StyleNamingTest {

    private val reviewedModelTypeNames = setOf(
        "StyleDocument",
        "StyleApp",
        "StyleFontFamilies",
        "StyleEffects",
        "StyleHome",
        "HomeSectionEntry",
        "StylePages",
        "StyleListLayout",
        "OrientationPair",
        "StyleNowPlaying",
        "StyleCompactPlayer",
        "StyleExpandedPlayer",
        "StyleTextBlocks",
        "StyleTextBlock",
        "StyleControlButton",
        "NowPlayingBlock",
        "StyleProgress",
        "StyleBackdrop",
        "StyleSolidBackdropLayer",
        "StyleGradientBackdropLayer",
        "StyleAlbumArtBackdropLayer",
        "StyleBlurBackdropLayer",
        "StyleGrainBackdropLayer",
        "StyleVignetteBackdropLayer",
        "StyleGradientMeshBackdropLayer",
    )

    private val documentedTokens: Map<Class<out Enum<*>>, List<String>> = mapOf(
        ColorSource::class.java to listOf("SYSTEM", "SEED"),
        DarkMode::class.java to listOf("SYSTEM", "LIGHT", "DARK", "BLACK"),
        ColorPalette::class.java to listOf(
            "TONAL_SPOT", "NEUTRAL", "VIBRANT", "EXPRESSIVE", "RAINBOW",
            "FRUIT_SALAD", "MONOCHROME", "FIDELITY", "CONTENT",
        ),
        ColorContrast::class.java to listOf("STANDARD", "MEDIUM", "HIGH"),
        CornerRoundness::class.java to listOf("DEFAULT", "MEDIUM", "LARGE", "EXTRA_LARGE"),
        QualityTier::class.java to listOf("AUTO", "LOW", "HIGH"),
        ProgressiveBlur::class.java to listOf("AUTO", "ON", "OFF"),
        HomeSection::class.java to listOf(
            "RECENTLY_PLAYED", "RECENTLY_ADDED", "MOST_PLAYED", "FAVORITES",
        ),
        NowPlayingBlockKind::class.java to listOf(
            "ARTWORK", "TITLE", "SUBTITLE", "PROGRESS", "CONTROLS", "QUEUE", "LYRICS",
        ),
        PlayerAction::class.java to listOf(
            "NONE", "PLAY_PAUSE", "PREVIOUS", "NEXT", "SHUFFLE", "REPEAT", "STOP",
            "SEEK_BACK_10", "SEEK_FORWARD_10", "OPEN_QUEUE", "OPEN_LYRICS", "FAVORITE",
            "SLEEP_TIMER",
        ),
        ControlButtonStyle::class.java to listOf("DEFAULT", "FILLED", "OUTLINED", "TEXT"),
        ControlButtonSize::class.java to listOf("SMALL", "MEDIUM", "LARGE"),
        TextAlignment::class.java to listOf("START", "CENTER", "END"),
        ProgressAppearance::class.java to listOf("SLIDER", "HAIRLINE"),
        BackdropLayerKind::class.java to listOf(
            "SOLID", "GRADIENT", "ALBUM_ART", "BLUR", "GRAIN", "VIGNETTE", "GRADIENT_MESH",
        ),
        BackdropBlendMode::class.java to listOf("NORMAL", "MULTIPLY", "SCREEN", "OVERLAY"),
        BackdropColor::class.java to listOf(
            "BACKGROUND", "SURFACE", "SURFACE_DIM", "SURFACE_CONTAINER_LOW",
            "SURFACE_CONTAINER", "SURFACE_CONTAINER_HIGH", "SCRIM", "PRIMARY",
        ),
        BackdropExtent::class.java to listOf("NOW_PLAYING", "FULL_SCREEN"),
        BackdropAnchor::class.java to listOf(
            "TOP", "BOTTOM", "TOP_AND_BOTTOM", "START", "END", "START_AND_END",
        ),
        BackdropBlurExtent::class.java to listOf("NOW_PLAYING", "NAV_BAR", "FULL_SCREEN"),
    )

    @Test
    fun `value vocabularies are closed and exactly documented`() {
        documentedTokens.forEach { (type, tokens) ->
            val actual = type.enumConstants.map { (it as Enum<*>).name }
            assertWithMessage(type.simpleName)
                .that(actual)
                .containsExactlyElementsIn(tokens)
                .inOrder()
        }
    }

    @Test
    fun `model keys are camelCase and never dotted`() {
        StyleModelSurface.propertyNames.forEach { key ->
            assertWithMessage("key '$key'").that(key).matches("^[a-z][A-Za-z0-9]*$")
        }
    }

    @Test
    fun `every model type is in the reviewed naming inventory`() {
        val unregistered = StyleModelSurface.modelTypes
            .map { it.simpleName }
            .filterNot { it in reviewedModelTypeNames }

        assertThat(unregistered).isEmpty()
    }

    @Test
    fun `seed color default is a portable hex literal`() {
        assertThat(StyleApp.DEFAULT_SEED_COLOR).matches("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    }
}
