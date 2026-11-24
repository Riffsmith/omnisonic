package app.omnisonic.style

import androidx.compose.ui.unit.dp
import app.omnisonic.core.model.style.BackdropAnchor
import app.omnisonic.core.model.style.BackdropBlendMode
import app.omnisonic.core.model.style.BackdropBlurExtent
import app.omnisonic.core.model.style.BackdropColor
import app.omnisonic.core.model.style.BackdropExtent
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.ProgressiveBlur
import app.omnisonic.core.model.style.QualityTier
import app.omnisonic.core.model.style.StyleAlbumArtBackdropLayer
import app.omnisonic.core.model.style.StyleBackdrop
import app.omnisonic.core.model.style.StyleBackdropLayer
import app.omnisonic.core.model.style.StyleBlurBackdropLayer
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleGradientBackdropLayer
import app.omnisonic.core.model.style.StyleGradientMeshBackdropLayer
import app.omnisonic.core.model.style.StyleGrainBackdropLayer
import app.omnisonic.core.model.style.StyleSolidBackdropLayer
import app.omnisonic.core.model.style.StyleVignetteBackdropLayer

/**
 * Pure profile → [StyleTokens] resolution (`docs/omnisonic/style/style-profiles.md`
 * §Resolution). The input is always a decoded (hence validated) [StyleDocument]
 * that the loader already merged over its built-in default, so this function has
 * no I/O, no Android context and no fallback path: unknown or out-of-range input
 * cannot exist here.
 *
 * Token groups the v1 document cannot express yet stay at [DefaultStyleTokens]:
 * colour-scheme derivation (TODO 6a/6b), the now-playing block/button/text
 * composition (TODO 8) and the per-list library geometry (TODO 7b). v1's single
 * `LayoutTokens` grid pair is driven by `pages.albums`.
 */
object StyleTokenResolver {

    fun resolve(document: StyleDocument): StyleTokens {
        val base = DefaultStyleTokens
        val app = document.app
        val effects = app.effects
        val pages = document.pages

        return StyleTokens(
            colors = base.colors,
            typography = base.typography,
            spacing = base.spacing,
            shapes = base.shapes.copy(cornerScale = app.cornerScale.toToken()),
            layout = base.layout.copy(
                gridColumnsPortrait = pages.albums.columns.portrait,
                gridColumnsLandscape = pages.albums.columns.landscape,
                gridSpacing = pages.albums.spacingDp.portrait.dp,
                miniPlayerProgressHeight = document.nowPlaying.compact.progress.heightDp.dp,
            ),
            imagery = base.imagery,
            surfaces = base.surfaces,
            effects = base.effects.copy(
                backdropStack = document.nowPlaying.background?.toStack() ?: base.effects.backdropStack,
                backdropOverlayAlpha = effects.overlayAlpha,
                backdropLegibilityShield = effects.legibilityShield,
                progressiveBlur = effects.progressiveBlur.toToken(),
                effectsQuality = effects.quality.toToken(),
                audioReactive = effects.audioReactive,
                artColorFollowing = effects.artColorFollowing,
                tiltParallaxEnabled = effects.tiltParallax,
                tiltParallaxMaxOffsetDp = effects.tiltParallaxMaxOffsetDp.dp,
            ),
            motion = base.motion,
            fonts = FontTokens(
                fontFamilies = FontFamilies(
                    title = app.fontFamilies.title,
                    body = app.fontFamilies.body,
                    lyrics = app.fontFamilies.lyrics,
                    nav = app.fontFamilies.nav,
                ),
                customFontUris = base.fonts.customFontUris,
            ),
        )
    }
}

private fun CornerRoundness.toToken(): CornerScale = when (this) {
    CornerRoundness.DEFAULT -> CornerScale.DEFAULT
    CornerRoundness.MEDIUM -> CornerScale.MEDIUM
    CornerRoundness.LARGE -> CornerScale.LARGE
    CornerRoundness.EXTRA_LARGE -> CornerScale.EXTRA_LARGE
}

private fun QualityTier.toToken(): EffectsQuality = when (this) {
    QualityTier.AUTO -> EffectsQuality.AUTO
    QualityTier.LOW -> EffectsQuality.LOW
    QualityTier.HIGH -> EffectsQuality.HIGH
}

private fun ProgressiveBlur.toToken(): ProgressiveBlurMode = when (this) {
    ProgressiveBlur.AUTO -> ProgressiveBlurMode.AUTO
    ProgressiveBlur.ON -> ProgressiveBlurMode.ON
    ProgressiveBlur.OFF -> ProgressiveBlurMode.OFF
}

private fun StyleBackdrop.toStack(): BackdropStack = BackdropStack(
    scope = extent.toToken(),
    layers = layers.map { it.toLayer() },
)

private fun BackdropExtent.toToken(): BackdropScope = when (this) {
    BackdropExtent.NOW_PLAYING -> BackdropScope.NOW_PLAYING
    BackdropExtent.FULL_SCREEN -> BackdropScope.FULL_SCREEN
}

private fun BackdropBlendMode.toToken(): BackdropBlend = when (this) {
    BackdropBlendMode.NORMAL -> BackdropBlend.NORMAL
    BackdropBlendMode.MULTIPLY -> BackdropBlend.MULTIPLY
    BackdropBlendMode.SCREEN -> BackdropBlend.SCREEN
    BackdropBlendMode.OVERLAY -> BackdropBlend.OVERLAY
}

private fun BackdropColor.toToken(): BackdropColorRole = when (this) {
    BackdropColor.BACKGROUND -> BackdropColorRole.BACKGROUND
    BackdropColor.SURFACE -> BackdropColorRole.SURFACE
    BackdropColor.SURFACE_DIM -> BackdropColorRole.SURFACE_DIM
    BackdropColor.SURFACE_CONTAINER_LOW -> BackdropColorRole.SURFACE_CONTAINER_LOW
    BackdropColor.SURFACE_CONTAINER -> BackdropColorRole.SURFACE_CONTAINER
    BackdropColor.SURFACE_CONTAINER_HIGH -> BackdropColorRole.SURFACE_CONTAINER_HIGH
    BackdropColor.SCRIM -> BackdropColorRole.SCRIM
    BackdropColor.PRIMARY -> BackdropColorRole.PRIMARY
}

private fun BackdropAnchor.toToken(): BackdropGradientAnchor = when (this) {
    BackdropAnchor.TOP -> BackdropGradientAnchor.TOP
    BackdropAnchor.BOTTOM -> BackdropGradientAnchor.BOTTOM
    BackdropAnchor.TOP_AND_BOTTOM -> BackdropGradientAnchor.TOP_AND_BOTTOM
    BackdropAnchor.START -> BackdropGradientAnchor.START
    BackdropAnchor.END -> BackdropGradientAnchor.END
    BackdropAnchor.START_AND_END -> BackdropGradientAnchor.START_AND_END
}

private fun BackdropBlurExtent.toToken(): BackdropBlurScope = when (this) {
    BackdropBlurExtent.NOW_PLAYING -> BackdropBlurScope.NOW_PLAYING
    BackdropBlurExtent.NAV_BAR -> BackdropBlurScope.NAV_BAR
    BackdropBlurExtent.FULL_SCREEN -> BackdropBlurScope.FULL_SCREEN
}

private fun StyleBackdropLayer.toLayer(): BackdropLayer = when (this) {
    is StyleSolidBackdropLayer -> SolidBackdropLayer(
        colorRole = color.toToken(),
        alpha = alpha,
        blend = blend.toToken(),
    )

    is StyleGradientBackdropLayer -> GradientBackdropLayer(
        anchor = anchor.toToken(),
        startColorRole = startColor.toToken(),
        endColorRole = endColor.toToken(),
        artTint = artTint,
        alpha = alpha,
        blend = blend.toToken(),
    )

    is StyleAlbumArtBackdropLayer -> AlbumArtBackdropLayer(
        scale = scale,
        alpha = alpha,
        blend = blend.toToken(),
    )

    is StyleBlurBackdropLayer -> BlurBackdropLayer(
        radiusDp = radiusDp.dp,
        scope = extent.toToken(),
        alpha = alpha,
        blend = blend.toToken(),
    )

    is StyleGrainBackdropLayer -> GrainBackdropLayer(
        density = density,
        alpha = alpha,
        blend = blend.toToken(),
    )

    is StyleVignetteBackdropLayer -> VignetteBackdropLayer(
        strength = strength,
        alpha = alpha,
        blend = blend.toToken(),
    )

    is StyleGradientMeshBackdropLayer -> GradientMeshBackdropLayer(
        intensity = intensity,
        alpha = alpha,
        blend = blend.toToken(),
    )
}
