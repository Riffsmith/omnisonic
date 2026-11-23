package app.omnisonic.core.model.style

/**
 * Document form of D-13's backdrop stack. `:core-model` cannot depend on
 * `:style`, so the resolver maps these layers onto `BackdropStack` rather than
 * storing Compose types in the document. Rendering and degradation stay with
 * `:style` (`docs/omnisonic/style/style-schema.md` §Model).
 */
data class StyleBackdrop(
    val extent: BackdropExtent = BackdropExtent.NOW_PLAYING,
    val layers: List<StyleBackdropLayer>,
)

sealed interface StyleBackdropLayer {
    val kind: BackdropLayerKind
    val alpha: Float
    val blend: BackdropBlendMode
}

enum class BackdropLayerKind { SOLID, GRADIENT, ALBUM_ART, BLUR, GRAIN, VIGNETTE, GRADIENT_MESH }

enum class BackdropBlendMode { NORMAL, MULTIPLY, SCREEN, OVERLAY }

enum class BackdropColor {
    BACKGROUND,
    SURFACE,
    SURFACE_DIM,
    SURFACE_CONTAINER_LOW,
    SURFACE_CONTAINER,
    SURFACE_CONTAINER_HIGH,
    SCRIM,
    PRIMARY,
}

enum class BackdropExtent { NOW_PLAYING, FULL_SCREEN }

enum class BackdropAnchor { TOP, BOTTOM, TOP_AND_BOTTOM, START, END, START_AND_END }

enum class BackdropBlurExtent { NOW_PLAYING, NAV_BAR, FULL_SCREEN }

data class StyleSolidBackdropLayer(
    val color: BackdropColor = BackdropColor.BACKGROUND,
    override val alpha: Float = 1f,
    override val blend: BackdropBlendMode = BackdropBlendMode.NORMAL,
) : StyleBackdropLayer {
    override val kind: BackdropLayerKind = BackdropLayerKind.SOLID
}

data class StyleGradientBackdropLayer(
    val anchor: BackdropAnchor = BackdropAnchor.TOP,
    val startColor: BackdropColor = BackdropColor.SURFACE_CONTAINER,
    val endColor: BackdropColor = BackdropColor.BACKGROUND,
    val artTint: Float = 0f,
    override val alpha: Float = 0.5f,
    override val blend: BackdropBlendMode = BackdropBlendMode.NORMAL,
) : StyleBackdropLayer {
    override val kind: BackdropLayerKind = BackdropLayerKind.GRADIENT
}

data class StyleAlbumArtBackdropLayer(
    val scale: Float = 1f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlendMode = BackdropBlendMode.NORMAL,
) : StyleBackdropLayer {
    override val kind: BackdropLayerKind = BackdropLayerKind.ALBUM_ART
}

data class StyleBlurBackdropLayer(
    val radiusDp: Float = 24f,
    val extent: BackdropBlurExtent = BackdropBlurExtent.NOW_PLAYING,
    override val alpha: Float = 1f,
    override val blend: BackdropBlendMode = BackdropBlendMode.NORMAL,
) : StyleBackdropLayer {
    override val kind: BackdropLayerKind = BackdropLayerKind.BLUR
}

data class StyleGrainBackdropLayer(
    val density: Float = 0.5f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlendMode = BackdropBlendMode.OVERLAY,
) : StyleBackdropLayer {
    override val kind: BackdropLayerKind = BackdropLayerKind.GRAIN
}

data class StyleVignetteBackdropLayer(
    val strength: Float = 0.5f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlendMode = BackdropBlendMode.MULTIPLY,
) : StyleBackdropLayer {
    override val kind: BackdropLayerKind = BackdropLayerKind.VIGNETTE
}

data class StyleGradientMeshBackdropLayer(
    val intensity: Float = 0.5f,
    override val alpha: Float = 1f,
    override val blend: BackdropBlendMode = BackdropBlendMode.NORMAL,
) : StyleBackdropLayer {
    override val kind: BackdropLayerKind = BackdropLayerKind.GRADIENT_MESH
}
