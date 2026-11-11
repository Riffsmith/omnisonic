package app.omnisonic.style

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Paints a [BackdropStack] behind its content (D-13). Renderers consume
 * exactly [BackdropStack.effectiveLayers] so the API/tier drop-below-floor
 * rules live in the tokens, never at the call site.
 *
 * Phase 5 draws the base layers — solid, gradient and album-art tint; the
 * AGSL effect layers (blur, grain, vignette, gradient mesh) are accepted by
 * the token model but not painted by this renderer yet (the Phase 9 effects
 * renderer owns them). The default "Subtle" stack is base-only, so the
 * default look is fully represented. [artColor] is the optional extracted
 * cover colour; without it gradient `artTint` falls back to the role colours.
 */
@Composable
fun BackdropSurface(
    modifier: Modifier = Modifier,
    stack: BackdropStack = OmnisonicTheme.tokens.effects.backdropStack,
    artColor: Color? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val tokens = OmnisonicTheme.tokens
    val effectsQuality = tokens.effects.effectsQuality
    val apiLevel = Build.VERSION.SDK_INT
    val layers = remember(stack, effectsQuality, apiLevel) {
        stack.effectiveLayers(effectsQuality, apiLevel)
    }
    Box(modifier) {
        layers.forEach { layer -> BackdropLayerPaint(layer, tokens.colors, artColor) }
        content()
    }
}

@Composable
private fun BoxScope.BackdropLayerPaint(
    layer: BackdropLayer,
    colors: ColorTokens,
    artColor: Color?,
) {
    when (layer) {
        is SolidBackdropLayer -> Box(
            Modifier
                .matchParentSize()
                .drawBehind { drawRect(layer.paintColor(colors), blendMode = layer.blend.toBlendMode()) },
        )

        is GradientBackdropLayer -> Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = layer.brush(colors, artColor, size.height, size.width),
                        blendMode = layer.blend.toBlendMode(),
                    )
                },
        )

        is AlbumArtBackdropLayer -> {
            val tint = artColor ?: return
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val radius = maxOf(size.width, size.height) * layer.scale
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(tint.copy(alpha = layer.alpha), tint.copy(alpha = 0f)),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = radius,
                            ),
                            blendMode = layer.blend.toBlendMode(),
                        )
                    },
            )
        }

        // Blur/grain/vignette/gradient mesh: token-valid, painted by the
        // Phase 9 effects renderer. Dropping them keeps legibility on the
        // base layers, which every stack carries.
        else -> Unit
    }
}

private fun SolidBackdropLayer.paintColor(colors: ColorTokens): Color {
    val base = colorRole.resolve(colors)
    return base.copy(alpha = base.alpha * alpha)
}

private fun GradientBackdropLayer.brush(
    colors: ColorTokens,
    artColor: Color?,
    height: Float,
    width: Float,
): Brush {
    val start = startColorRole.resolve(colors).tinted(artColor, artTint)
    val end = endColorRole.resolve(colors).tinted(artColor, artTint)
    val stops = listOf(start.copy(alpha = alpha), end.copy(alpha = alpha))
    return when (anchor) {
        BackdropGradientAnchor.TOP -> Brush.verticalGradient(stops, startY = 0f, endY = height)
        BackdropGradientAnchor.BOTTOM -> Brush.verticalGradient(stops.reversed(), startY = 0f, endY = height)
        BackdropGradientAnchor.TOP_AND_BOTTOM ->
            Brush.verticalGradient(listOf(stops[0], stops[1], stops[0]), startY = 0f, endY = height)
        BackdropGradientAnchor.START -> Brush.horizontalGradient(stops, startX = 0f, endX = width)
        BackdropGradientAnchor.END -> Brush.horizontalGradient(stops.reversed(), startX = 0f, endX = width)
        BackdropGradientAnchor.START_AND_END ->
            Brush.horizontalGradient(listOf(stops[0], stops[1], stops[0]), startX = 0f, endX = width)
    }
}

private fun Color.tinted(artColor: Color?, strength: Float): Color =
    if (artColor == null || strength <= 0f) this else lerp(this, artColor, strength.coerceIn(0f, 1f))

private fun BackdropBlend.toBlendMode(): BlendMode = when (this) {
    BackdropBlend.NORMAL -> BlendMode.SrcOver
    BackdropBlend.MULTIPLY -> BlendMode.Multiply
    BackdropBlend.SCREEN -> BlendMode.Screen
    BackdropBlend.OVERLAY -> BlendMode.Overlay
}
