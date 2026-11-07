package app.omnisonic.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** AGSL/RenderEffect floor shared by grain, vignette, gradient mesh and progressive blur. */
const val AGSL_MIN_API = 33

/** RenderEffect blur floor from the capability matrix (reference/docs/GESTURES.md §3). */
const val BLUR_MIN_API = 31

/** Backdrop layers draw bottom-up; the first layer is the base every later layer sits on. */
enum class BackdropLayerType { SOLID, GRADIENT, ALBUM_ART, BLUR, GRAIN, VIGNETTE, GRADIENT_MESH }

enum class BackdropBlend { NORMAL, MULTIPLY, SCREEN, OVERLAY }

enum class BackdropColorRole {
    BACKGROUND, SURFACE, SURFACE_DIM,
    SURFACE_CONTAINER_LOW, SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH,
    SCRIM, PRIMARY,
}

fun BackdropColorRole.resolve(colors: ColorTokens): Color = when (this) {
    BackdropColorRole.BACKGROUND -> colors.background
    BackdropColorRole.SURFACE -> colors.surface
    BackdropColorRole.SURFACE_DIM -> colors.surfaceDim
    BackdropColorRole.SURFACE_CONTAINER_LOW -> colors.surfaceContainerLow
    BackdropColorRole.SURFACE_CONTAINER -> colors.surfaceContainer
    BackdropColorRole.SURFACE_CONTAINER_HIGH -> colors.surfaceContainerHigh
    BackdropColorRole.SCRIM -> colors.scrim
    BackdropColorRole.PRIMARY -> colors.primary
}

/** Screen extent a backdrop paints: the player pane only, or edge to edge including system-bar areas. */
enum class BackdropScope { NOW_PLAYING, FULL_SCREEN }

enum class BackdropGradientAnchor { TOP, BOTTOM, TOP_AND_BOTTOM, START, END, START_AND_END }

enum class BackdropBlurScope { NOW_PLAYING, NAV_BAR, FULL_SCREEN }

@Immutable
sealed interface BackdropLayer {
    val type: BackdropLayerType
    val alpha: Float
    val blend: BackdropBlend

    /** False means renderers drop the layer at this API level; every layer drops below its floor. */
    fun supportedOn(apiLevel: Int): Boolean = true

    fun validate() {
        require(alpha in VALID_ALPHA) { "alpha must be within 0..1 (was $alpha)" }
    }

    companion object {
        val VALID_ALPHA = 0f..1f
    }
}

@Immutable
data class SolidBackdropLayer(
    val colorRole: BackdropColorRole = BackdropColorRole.BACKGROUND,
    override val alpha: Float = 1f,
    override val blend: BackdropBlend = BackdropBlend.NORMAL,
) : BackdropLayer {
    override val type = BackdropLayerType.SOLID

    init {
        validate()
    }
}

@Immutable
data class GradientBackdropLayer(
    val anchor: BackdropGradientAnchor = BackdropGradientAnchor.TOP,
    val startColorRole: BackdropColorRole = BackdropColorRole.SURFACE_CONTAINER,
    val endColorRole: BackdropColorRole = BackdropColorRole.BACKGROUND,
    val artTint: Float = 0f,
    override val alpha: Float = 0.5f,
    override val blend: BackdropBlend = BackdropBlend.NORMAL,
) : BackdropLayer {
    override val type = BackdropLayerType.GRADIENT

    init {
        require(artTint in BackdropLayer.VALID_ALPHA) { "artTint must be within 0..1 (was $artTint)" }
        validate()
    }
}

@Immutable
data class AlbumArtBackdropLayer(
    val scale: Float = 1f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlend = BackdropBlend.NORMAL,
) : BackdropLayer {
    override val type = BackdropLayerType.ALBUM_ART

    init {
        require(scale > 0f) { "scale must be positive (was $scale)" }
        validate()
    }
}

@Immutable
data class BlurBackdropLayer(
    val radiusDp: Dp = 24.dp,
    val scope: BackdropBlurScope = BackdropBlurScope.NOW_PLAYING,
    override val alpha: Float = 1f,
    override val blend: BackdropBlend = BackdropBlend.NORMAL,
) : BackdropLayer {
    override val type = BackdropLayerType.BLUR

    init {
        require(radiusDp.value >= 0f) { "radius must be non-negative (was $radiusDp)" }
        validate()
    }

    override fun supportedOn(apiLevel: Int): Boolean = apiLevel >= BLUR_MIN_API
}

@Immutable
data class GrainBackdropLayer(
    val density: Float = 0.5f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlend = BackdropBlend.OVERLAY,
) : BackdropLayer {
    override val type = BackdropLayerType.GRAIN

    init {
        require(density in BackdropLayer.VALID_ALPHA) { "density must be within 0..1 (was $density)" }
        validate()
    }

    override fun supportedOn(apiLevel: Int): Boolean = apiLevel >= AGSL_MIN_API
}

@Immutable
data class VignetteBackdropLayer(
    val strength: Float = 0.5f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlend = BackdropBlend.MULTIPLY,
) : BackdropLayer {
    override val type = BackdropLayerType.VIGNETTE

    init {
        require(strength in BackdropLayer.VALID_ALPHA) { "strength must be within 0..1 (was $strength)" }
        validate()
    }

    override fun supportedOn(apiLevel: Int): Boolean = apiLevel >= AGSL_MIN_API
}

@Immutable
data class GradientMeshBackdropLayer(
    val intensity: Float = 0.5f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlend = BackdropBlend.NORMAL,
) : BackdropLayer {
    override val type = BackdropLayerType.GRADIENT_MESH

    init {
        require(intensity in BackdropLayer.VALID_ALPHA) { "intensity must be within 0..1 (was $intensity)" }
        validate()
    }

    override fun supportedOn(apiLevel: Int): Boolean = apiLevel >= AGSL_MIN_API
}

@Immutable
data class BackdropStack(
    val scope: BackdropScope = BackdropScope.NOW_PLAYING,
    val layers: List<BackdropLayer>,
) {
    init {
        require(layers.isNotEmpty()) { "a backdrop needs at least one layer" }
        layers.forEach { it.validate() }
    }

    /**
     * Layers a renderer may draw at this tier and API level: API-33-only layers drop
     * below their floor and at the low tier, and blur radius caps at the low tier
     * (reference/docs/UI_SPEC.md §4). Base layers always survive so legibility never
     * depends on an effect.
     */
    fun effectiveLayers(quality: EffectsQuality, apiLevel: Int): List<BackdropLayer> {
        val tier = quality.resolveTier()
        return layers.mapNotNull { layer ->
            when {
                !layer.supportedOn(apiLevel) -> null
                tier == EffectsQuality.LOW && layer.type in AGSL_ONLY_TYPES -> null
                layer is BlurBackdropLayer -> layer.copy(radiusDp = tier.capBlurRadius(layer.radiusDp))
                else -> layer
            }
        }
    }

    companion object {
        val AGSL_ONLY_TYPES = setOf(
            BackdropLayerType.GRAIN,
            BackdropLayerType.VIGNETTE,
            BackdropLayerType.GRADIENT_MESH,
        )
    }
}

/**
 * The 1c preset values as ready-made stacks (D-13): presets no longer cap
 * customization, they name complete examples; Phase 9 stores stacks directly.
 * The default look is "Subtle" — a clean dark base with a soft gradient that
 * may pick up art colors when the renderer extracts them.
 */
object DefaultBackdropStacks {
    val SUBTLE = BackdropStack(
        layers = listOf(
            SolidBackdropLayer(colorRole = BackdropColorRole.BACKGROUND),
            GradientBackdropLayer(
                anchor = BackdropGradientAnchor.TOP,
                startColorRole = BackdropColorRole.SURFACE_CONTAINER,
                endColorRole = BackdropColorRole.BACKGROUND,
                artTint = 0.35f,
                alpha = 0.35f,
            ),
        ),
    )
    val SOLID = BackdropStack(layers = listOf(SolidBackdropLayer(colorRole = BackdropColorRole.BACKGROUND)))
    val GRADIENT = BackdropStack(
        layers = listOf(
            SolidBackdropLayer(colorRole = BackdropColorRole.BACKGROUND),
            GradientBackdropLayer(anchor = BackdropGradientAnchor.TOP),
        ),
    )
    val ART_BLURRED = BackdropStack(
        layers = listOf(
            AlbumArtBackdropLayer(),
            BlurBackdropLayer(radiusDp = 24.dp, scope = BackdropBlurScope.NOW_PLAYING),
            SolidBackdropLayer(colorRole = BackdropColorRole.SCRIM, alpha = 0.4f),
        ),
    )
    val CARD = BackdropStack(
        layers = listOf(SolidBackdropLayer(colorRole = BackdropColorRole.SURFACE_CONTAINER_HIGH, alpha = 0.92f)),
    )
    val FULL_BAR = BackdropStack(
        scope = BackdropScope.FULL_SCREEN,
        layers = listOf(SolidBackdropLayer(colorRole = BackdropColorRole.SURFACE)),
    )
}

/**
 * Scrim opacity that keeps text over a backdrop readable. Luminances are
 * sRGB-relative values in 0..1; the scrim is the black scrim token, so a
 * composited backdrop reads as `backdrop * (1 - alpha)`. Darkening can never
 * rescue dark text on a dark backdrop — those pass the manual alpha through.
 */
object LegibilityShield {
    const val MIN_CONTRAST_RATIO = 4.5f

    fun scrimAlpha(
        backdropLuminance: Float,
        textLuminance: Float,
        manualScrimAlpha: Float,
        autoShieldEnabled: Boolean,
        minContrast: Float = MIN_CONTRAST_RATIO,
    ): Float {
        val base = manualScrimAlpha.coerceIn(0f, 1f)
        if (!autoShieldEnabled) return base
        val backdrop = backdropLuminance.coerceIn(0f, 1f)
        val text = textLuminance.coerceIn(0f, 1f)
        if (text <= backdrop) return base
        val target = (text + 0.05f) / minContrast - 0.05f
        if (backdrop <= target) return base
        return maxOf(base, 1f - target / backdrop).coerceIn(0f, 1f)
    }
}
