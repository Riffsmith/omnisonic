package app.omnisonic.style

import androidx.compose.ui.unit.dp
import app.omnisonic.core.model.style.BackdropAnchor
import app.omnisonic.core.model.style.BackdropBlendMode
import app.omnisonic.core.model.style.BackdropBlurExtent
import app.omnisonic.core.model.style.BackdropColor
import app.omnisonic.core.model.style.BackdropExtent
import app.omnisonic.core.model.style.BackdropLayerKind
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.ColorContrast
import app.omnisonic.core.model.style.ColorPalette
import app.omnisonic.core.model.style.ColorSource
import app.omnisonic.core.model.style.DarkMode
import app.omnisonic.core.model.style.ProgressiveBlur
import app.omnisonic.core.model.style.QualityTier
import app.omnisonic.core.model.style.StyleAlbumArtBackdropLayer
import app.omnisonic.core.model.style.StyleApp
import app.omnisonic.core.model.style.StyleBackdrop
import app.omnisonic.core.model.style.StyleBackdropLayer
import app.omnisonic.core.model.style.StyleBlurBackdropLayer
import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleEffects
import app.omnisonic.core.model.style.StyleGradientBackdropLayer
import app.omnisonic.core.model.style.StyleGradientMeshBackdropLayer
import app.omnisonic.core.model.style.StyleGrainBackdropLayer
import app.omnisonic.core.model.style.StyleSolidBackdropLayer
import app.omnisonic.core.model.style.StyleVignetteBackdropLayer
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Pins the pure profile → [StyleTokens] resolution against the shipped built-ins
 * and the full v1 enum surface (`docs/omnisonic/style/style-profiles.md` §Resolution).
 */
class StyleTokenResolverTest {

    private val assetDir = File("src/main/assets/styles")

    private fun defaultDocument(): StyleDocument =
        StyleDocument(id = "builtin-default", name = "Default Profile")

    private fun decodedAsset(id: String): StyleDocument {
        val file = File(assetDir, "$id.json")
        assertThat(file.isFile).isTrue()
        val result = StyleDecoder.decode(file.readText())
        assertThat(result).isInstanceOf(StyleDecodeResult.Decoded::class.java)
        return (result as StyleDecodeResult.Decoded).document
    }

    private fun resolvedAsset(id: String): StyleTokens = StyleTokenResolver.resolve(decodedAsset(id))

    private fun documentWithBackdrop(
        layers: List<StyleBackdropLayer>,
        extent: BackdropExtent = BackdropExtent.NOW_PLAYING,
    ): StyleDocument {
        val base = defaultDocument()
        return base.copy(
            nowPlaying = base.nowPlaying.copy(
                background = StyleBackdrop(extent = extent, layers = layers),
            ),
        )
    }

    private fun resolvedBackdrop(layers: List<StyleBackdropLayer>): BackdropStack =
        StyleTokenResolver.resolve(documentWithBackdrop(layers)).effects.backdropStack

    private fun layerOf(kind: BackdropLayerKind): StyleBackdropLayer = when (kind) {
        BackdropLayerKind.SOLID -> StyleSolidBackdropLayer()
        BackdropLayerKind.GRADIENT -> StyleGradientBackdropLayer()
        BackdropLayerKind.ALBUM_ART -> StyleAlbumArtBackdropLayer()
        BackdropLayerKind.BLUR -> StyleBlurBackdropLayer()
        BackdropLayerKind.GRAIN -> StyleGrainBackdropLayer()
        BackdropLayerKind.VIGNETTE -> StyleVignetteBackdropLayer()
        BackdropLayerKind.GRADIENT_MESH -> StyleGradientMeshBackdropLayer()
    }

    @Test
    fun defaultProfileResolvesExactlyToDefaultStyleTokens() {
        assertThat(defaultDocument().app).isEqualTo(StyleApp())
        assertThat(StyleTokenResolver.resolve(defaultDocument())).isEqualTo(DefaultStyleTokens)
    }

    @Test
    fun theDefaultBuiltinAssetResolvesExactlyToDefaultStyleTokens() {
        assertThat(resolvedAsset("builtin-default")).isEqualTo(DefaultStyleTokens)
    }

    @Test
    fun libraryAndTabletBuiltinsResolveToTheirGridAndCornerValues() {
        val compact = resolvedAsset("builtin-compact-list").layout
        assertThat(compact.gridColumnsPortrait).isEqualTo(4)
        assertThat(compact.gridColumnsLandscape).isEqualTo(6)
        assertThat(compact.gridSpacing).isEqualTo(4.dp)
        assertThat(resolvedAsset("builtin-compact-list").shapes.cornerScale)
            .isEqualTo(CornerScale.DEFAULT)

        val large = resolvedAsset("builtin-large-cover").layout
        assertThat(large.gridColumnsPortrait).isEqualTo(2)
        assertThat(large.gridColumnsLandscape).isEqualTo(3)
        assertThat(large.gridSpacing).isEqualTo(12.dp)

        val tablet = resolvedAsset("builtin-tablet-wide")
        assertThat(tablet.layout.gridColumnsPortrait).isEqualTo(5)
        assertThat(tablet.layout.gridColumnsLandscape).isEqualTo(8)
        assertThat(tablet.layout.gridSpacing).isEqualTo(12.dp)
        assertThat(tablet.shapes.cornerScale).isEqualTo(CornerScale.LARGE)
        assertThat(tablet.shapes.extraLarge).isEqualTo(42.dp)
    }

    @Test
    fun artworkBuiltinResolvesItsBackdropLayerStack() {
        val stack = resolvedAsset("builtin-player-artwork").effects.backdropStack

        assertThat(stack.scope).isEqualTo(BackdropScope.FULL_SCREEN)
        assertThat(stack.layers.map { it.type }).containsExactly(
            BackdropLayerType.ALBUM_ART,
            BackdropLayerType.BLUR,
            BackdropLayerType.GRADIENT,
        ).inOrder()

        val art = stack.layers[0] as AlbumArtBackdropLayer
        assertThat(art.scale).isEqualTo(1.2f)
        val blur = stack.layers[1] as BlurBackdropLayer
        assertThat(blur.radiusDp).isEqualTo(48.dp)
        assertThat(blur.scope).isEqualTo(BackdropBlurScope.FULL_SCREEN)
        assertThat(blur.alpha).isWithin(0.001f).of(0.6f)
        val gradient = stack.layers[2] as GradientBackdropLayer
        assertThat(gradient.anchor).isEqualTo(BackdropGradientAnchor.TOP_AND_BOTTOM)
        assertThat(gradient.startColorRole).isEqualTo(BackdropColorRole.SCRIM)
    }

    @Test
    fun nowPlayingBuiltinsWhoseDistinctionIsNotTokenisedKeepTheBaseTokens() {
        // Minimal Player's and Queue Player's distinguishing values are now-playing
        // block/button/progress composition, which v1 has no token group for (TODO 8).
        assertThat(resolvedAsset("builtin-player-minimal")).isEqualTo(DefaultStyleTokens)
        assertThat(resolvedAsset("builtin-player-queue")).isEqualTo(DefaultStyleTokens)
        assertThat(resolvedAsset("builtin-player-artwork")).isNotEqualTo(DefaultStyleTokens)
    }

    @Test
    fun resolutionIsDeterministicAcrossRepeatedCallsAndFreshDecodes() {
        val document = decodedAsset("builtin-compact-list")
        val first = StyleTokenResolver.resolve(document)
        val second = StyleTokenResolver.resolve(document)
        val third = StyleTokenResolver.resolve(decodedAsset("builtin-compact-list"))

        assertThat(second).isEqualTo(first)
        assertThat(third).isEqualTo(first)
        assertThat(StyleTokenResolver.resolve(defaultDocument()))
            .isEqualTo(StyleTokenResolver.resolve(defaultDocument()))
    }

    @Test
    fun everyCornerRoundnessMapsToItsCornerScale() {
        val expected = mapOf(
            CornerRoundness.DEFAULT to CornerScale.DEFAULT,
            CornerRoundness.MEDIUM to CornerScale.MEDIUM,
            CornerRoundness.LARGE to CornerScale.LARGE,
            CornerRoundness.EXTRA_LARGE to CornerScale.EXTRA_LARGE,
        )
        assertThat(expected.keys).containsExactlyElementsIn(CornerRoundness.entries)

        for ((roundness, scale) in expected) {
            val shapes = StyleTokenResolver.resolve(
                defaultDocument().copy(app = StyleApp(cornerScale = roundness)),
            ).shapes
            assertThat(shapes.cornerScale).isEqualTo(scale)
            assertThat(shapes.small).isEqualTo(scale.small)
            assertThat(shapes.extraLarge).isEqualTo(scale.extraLarge)
            assertThat(shapes.cover).isEqualTo(DefaultStyleTokens.shapes.cover)
            assertThat(shapes.pill).isEqualTo(DefaultStyleTokens.shapes.pill)
        }
    }

    @Test
    fun everyQualityTierAndProgressiveBlurMapsToItsEffectToken() {
        val qualities = mapOf(
            QualityTier.AUTO to EffectsQuality.AUTO,
            QualityTier.LOW to EffectsQuality.LOW,
            QualityTier.HIGH to EffectsQuality.HIGH,
        )
        val blurs = mapOf(
            ProgressiveBlur.AUTO to ProgressiveBlurMode.AUTO,
            ProgressiveBlur.ON to ProgressiveBlurMode.ON,
            ProgressiveBlur.OFF to ProgressiveBlurMode.OFF,
        )
        assertThat(qualities.keys).containsExactlyElementsIn(QualityTier.entries)
        assertThat(blurs.keys).containsExactlyElementsIn(ProgressiveBlur.entries)

        for ((tier, token) in qualities) {
            val effects = StyleTokenResolver.resolve(
                defaultDocument().copy(app = StyleApp(effects = StyleEffects(quality = tier))),
            ).effects
            assertThat(effects.effectsQuality).isEqualTo(token)
        }
        for ((blur, token) in blurs) {
            val effects = StyleTokenResolver.resolve(
                defaultDocument().copy(app = StyleApp(effects = StyleEffects(progressiveBlur = blur))),
            ).effects
            assertThat(effects.progressiveBlur).isEqualTo(token)
        }
    }

    @Test
    fun everyBackdropColourBlendAnchorAndLayerKindMapsToItsTokenCounterpart() {
        for (color in BackdropColor.entries) {
            val layer = resolvedBackdrop(listOf(StyleSolidBackdropLayer(color = color))).layers.single()
            assertThat((layer as SolidBackdropLayer).colorRole)
                .isEqualTo(BackdropColorRole.valueOf(color.name))
        }

        for (blend in BackdropBlendMode.entries) {
            val layer = resolvedBackdrop(listOf(StyleSolidBackdropLayer(blend = blend))).layers.single()
            assertThat(layer.blend).isEqualTo(BackdropBlend.valueOf(blend.name))
        }

        for (anchor in BackdropAnchor.entries) {
            val layer = resolvedBackdrop(
                listOf(StyleGradientBackdropLayer(anchor = anchor, startColor = BackdropColor.PRIMARY)),
            ).layers.single() as GradientBackdropLayer
            assertThat(layer.anchor).isEqualTo(BackdropGradientAnchor.valueOf(anchor.name))
            assertThat(layer.startColorRole).isEqualTo(BackdropColorRole.PRIMARY)
            assertThat(layer.endColorRole).isEqualTo(BackdropColorRole.BACKGROUND)
        }

        for (extent in BackdropBlurExtent.entries) {
            val layer = resolvedBackdrop(
                listOf(StyleBlurBackdropLayer(extent = extent, radiusDp = 12f)),
            ).layers.single() as BlurBackdropLayer
            assertThat(layer.scope).isEqualTo(BackdropBlurScope.valueOf(extent.name))
            assertThat(layer.radiusDp).isEqualTo(12.dp)
        }

        for (kind in BackdropLayerKind.entries) {
            val layer = resolvedBackdrop(listOf(layerOf(kind))).layers.single()
            assertThat(layer.type).isEqualTo(BackdropLayerType.valueOf(kind.name))
        }

        val portrait = StyleTokenResolver.resolve(
            documentWithBackdrop(listOf(StyleSolidBackdropLayer()), extent = BackdropExtent.NOW_PLAYING),
        ).effects.backdropStack
        assertThat(portrait.scope).isEqualTo(BackdropScope.NOW_PLAYING)
        val full = StyleTokenResolver.resolve(
            documentWithBackdrop(listOf(StyleSolidBackdropLayer()), extent = BackdropExtent.FULL_SCREEN),
        ).effects.backdropStack
        assertThat(full.scope).isEqualTo(BackdropScope.FULL_SCREEN)
    }

    @Test
    fun pagesAndCompactProgressDriveLayoutWhileOtherListsStayUntokenised() {
        val base = defaultDocument()
        val pages = base.pages.copy(
            albums = base.pages.albums.copy(
                columns = app.omnisonic.core.model.style.OrientationPair(portrait = 7, landscape = 9),
                spacingDp = app.omnisonic.core.model.style.OrientationPair(portrait = 5f, landscape = 20f),
            ),
            playlists = base.pages.playlists.copy(
                columns = app.omnisonic.core.model.style.OrientationPair(portrait = 2, landscape = 2),
            ),
        )
        val compact = base.nowPlaying.compact.copy(
            progress = base.nowPlaying.compact.progress.copy(heightDp = 6f),
        )
        val layout = StyleTokenResolver.resolve(
            base.copy(pages = pages, nowPlaying = base.nowPlaying.copy(compact = compact)),
        ).layout

        assertThat(layout.gridColumnsPortrait).isEqualTo(7)
        assertThat(layout.gridColumnsLandscape).isEqualTo(9)
        assertThat(layout.gridSpacing).isEqualTo(5.dp)
        assertThat(layout.gridColumnsFor(isLandscape = true)).isEqualTo(9)
        assertThat(layout.miniPlayerProgressHeight).isEqualTo(6.dp)

        val playlistsOnly = StyleTokenResolver.resolve(
            base.copy(
                pages = base.pages.copy(
                    playlists = base.pages.playlists.copy(
                        columns = app.omnisonic.core.model.style.OrientationPair(portrait = 2, landscape = 2),
                    ),
                ),
            ),
        ).layout
        assertThat(playlistsOnly).isEqualTo(DefaultStyleTokens.layout)
    }

    @Test
    fun fontFamiliesAndEffectFlagsResolveFieldByField() {
        val effects = StyleEffects(
            overlayAlpha = 0.7f,
            legibilityShield = false,
            artColorFollowing = true,
            audioReactive = true,
            tiltParallax = true,
            tiltParallaxMaxOffsetDp = 20f,
        )
        val app = StyleApp(
            fontFamilies = app.omnisonic.core.model.style.StyleFontFamilies(
                title = "Title Face",
                body = "Body Face",
                lyrics = "Lyrics Face",
                nav = "Nav Face",
            ),
            effects = effects,
        )
        val tokens = StyleTokenResolver.resolve(defaultDocument().copy(app = app))

        assertThat(tokens.fonts.fontFamilies).isEqualTo(
            FontFamilies(
                title = "Title Face",
                body = "Body Face",
                lyrics = "Lyrics Face",
                nav = "Nav Face",
            ),
        )
        assertThat(tokens.fonts.customFontUris).isEmpty()
        assertThat(tokens.effects.backdropOverlayAlpha).isWithin(0.001f).of(0.7f)
        assertThat(tokens.effects.backdropLegibilityShield).isFalse()
        assertThat(tokens.effects.artColorFollowing).isTrue()
        assertThat(tokens.effects.audioReactive).isTrue()
        assertThat(tokens.effects.tiltParallaxEnabled).isTrue()
        assertThat(tokens.effects.tiltParallaxMaxOffsetDp).isEqualTo(20.dp)
        assertThat(tokens.typography).isEqualTo(DefaultStyleTokens.typography)
    }

    @Test
    fun colourEnumsLeaveTheBasePaletteUntilSchemeDerivationExists() {
        val base = DefaultStyleTokens.colors
        for (source in ColorSource.entries) {
            for (mode in DarkMode.entries) {
                for (palette in ColorPalette.entries) {
                    for (contrast in ColorContrast.entries) {
                        val tokens = StyleTokenResolver.resolve(
                            defaultDocument().copy(
                                app = StyleApp(
                                    colorSource = source,
                                    seedColor = "#112233",
                                    darkMode = mode,
                                    colorPalette = palette,
                                    colorContrast = contrast,
                                ),
                            ),
                        )
                        assertThat(tokens.colors).isEqualTo(base)
                    }
                }
            }
        }
    }

    @Test
    fun outOfSchemaInputCannotProduceADocumentToResolve() {
        val url = requireNotNull(javaClass.classLoader.getResource("style-samples/broken-builtin.json")) {
            "broken-builtin.json missing from test resources"
        }
        val result = StyleDecoder.decode(File(url.toURI()).readText())

        // The resolver only accepts StyleDocument, which only a successful decode yields.
        assertThat(result).isInstanceOf(StyleDecodeResult.Rejected::class.java)
        val rejected = result as StyleDecodeResult.Rejected
        assertThat(rejected.errors).isNotEmpty()
        assertThat(rejected.firstMessage)
            .isEqualTo("pages.albums.columns.portrait: value 0 is out of range 1..12")
    }
}
