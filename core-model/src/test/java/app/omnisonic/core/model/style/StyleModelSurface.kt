package app.omnisonic.core.model.style

import java.lang.reflect.Modifier

internal object StyleModelSurface {

    val modelTypes: List<Class<*>> = listOf(
        StyleDocument::class.java,
        StyleApp::class.java,
        StyleFontFamilies::class.java,
        StyleEffects::class.java,
        StyleHome::class.java,
        HomeSectionEntry::class.java,
        StylePages::class.java,
        StyleListLayout::class.java,
        OrientationPair::class.java,
        StyleNowPlaying::class.java,
        StyleCompactPlayer::class.java,
        StyleExpandedPlayer::class.java,
        StyleTextBlocks::class.java,
        StyleTextBlock::class.java,
        StyleControlButton::class.java,
        NowPlayingBlock::class.java,
        StyleProgress::class.java,
        StyleBackdrop::class.java,
        StyleSolidBackdropLayer::class.java,
        StyleGradientBackdropLayer::class.java,
        StyleAlbumArtBackdropLayer::class.java,
        StyleBlurBackdropLayer::class.java,
        StyleGrainBackdropLayer::class.java,
        StyleVignetteBackdropLayer::class.java,
        StyleGradientMeshBackdropLayer::class.java,
    )

    val propertyNames: List<String> = modelTypes.flatMap { type ->
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
    }
}
