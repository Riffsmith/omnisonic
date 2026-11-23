package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StyleBackdropTest {

    private val layers = listOf(
        StyleSolidBackdropLayer(),
        StyleGradientBackdropLayer(),
        StyleAlbumArtBackdropLayer(),
        StyleBlurBackdropLayer(),
        StyleGrainBackdropLayer(),
        StyleVignetteBackdropLayer(),
        StyleGradientMeshBackdropLayer(),
    )

    @Test
    fun `each backdrop layer declares its own kind`() {
        assertThat(layers.map { it.kind }).containsExactly(
            BackdropLayerKind.SOLID,
            BackdropLayerKind.GRADIENT,
            BackdropLayerKind.ALBUM_ART,
            BackdropLayerKind.BLUR,
            BackdropLayerKind.GRAIN,
            BackdropLayerKind.VIGNETTE,
            BackdropLayerKind.GRADIENT_MESH,
        ).inOrder()
    }

    @Test
    fun `backdrop layer defaults match the Phase 5 stack`() {
        assertThat(StyleSolidBackdropLayer()).isEqualTo(
            StyleSolidBackdropLayer(
                color = BackdropColor.BACKGROUND,
                alpha = 1f,
                blend = BackdropBlendMode.NORMAL,
            ),
        )
        assertThat(StyleGradientBackdropLayer()).isEqualTo(
            StyleGradientBackdropLayer(
                anchor = BackdropAnchor.TOP,
                startColor = BackdropColor.SURFACE_CONTAINER,
                endColor = BackdropColor.BACKGROUND,
                artTint = 0f,
                alpha = 0.5f,
                blend = BackdropBlendMode.NORMAL,
            ),
        )
        assertThat(StyleAlbumArtBackdropLayer()).isEqualTo(
            StyleAlbumArtBackdropLayer(
                scale = 1f,
                alpha = 1f,
                blend = BackdropBlendMode.NORMAL,
            ),
        )
        assertThat(StyleBlurBackdropLayer()).isEqualTo(
            StyleBlurBackdropLayer(
                radiusDp = 24f,
                extent = BackdropBlurExtent.NOW_PLAYING,
                alpha = 1f,
                blend = BackdropBlendMode.NORMAL,
            ),
        )
        assertThat(StyleGrainBackdropLayer()).isEqualTo(
            StyleGrainBackdropLayer(
                density = 0.5f,
                alpha = 1f,
                blend = BackdropBlendMode.OVERLAY,
            ),
        )
        assertThat(StyleVignetteBackdropLayer()).isEqualTo(
            StyleVignetteBackdropLayer(
                strength = 0.5f,
                alpha = 1f,
                blend = BackdropBlendMode.MULTIPLY,
            ),
        )
        assertThat(StyleGradientMeshBackdropLayer()).isEqualTo(
            StyleGradientMeshBackdropLayer(
                intensity = 0.5f,
                alpha = 1f,
                blend = BackdropBlendMode.NORMAL,
            ),
        )
        assertThat(StyleBackdrop(layers = layers).extent).isEqualTo(BackdropExtent.NOW_PLAYING)
    }

    @Test
    fun `an absent background keeps the default stack and a present one preserves order`() {
        assertThat(StyleNowPlaying().background).isNull()

        val ordered = listOf(
            StyleAlbumArtBackdropLayer(),
            StyleBlurBackdropLayer(),
            StyleSolidBackdropLayer(color = BackdropColor.SCRIM, alpha = 0.4f),
        )
        val nowPlaying = StyleNowPlaying(background = StyleBackdrop(layers = ordered))

        assertThat(nowPlaying.background?.layers).containsExactlyElementsIn(ordered).inOrder()
    }
}
