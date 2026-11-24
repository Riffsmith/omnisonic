package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class StyleDocumentTest {

    @Test
    fun `a minimal document equals the documented defaults`() {
        val minimal = StyleDocument(id = "style-id", name = "Style name")

        assertThat(minimal).isEqualTo(
            StyleDocument(
                id = "style-id",
                name = "Style name",
                schemaVersion = 1,
                app = StyleApp(
                    colorSource = ColorSource.SYSTEM,
                    seedColor = "#7CC4FF",
                    darkMode = DarkMode.SYSTEM,
                    colorPalette = ColorPalette.TONAL_SPOT,
                    colorContrast = ColorContrast.STANDARD,
                    cornerScale = CornerRoundness.DEFAULT,
                    fontFamilies = StyleFontFamilies(title = null, body = null, lyrics = null, nav = null),
                    effects = StyleEffects(
                        quality = QualityTier.AUTO,
                        progressiveBlur = ProgressiveBlur.AUTO,
                        overlayAlpha = 0.4f,
                        legibilityShield = true,
                        artColorFollowing = false,
                        audioReactive = false,
                        tiltParallax = false,
                        tiltParallaxMaxOffsetDp = 12f,
                    ),
                ),
                home = StyleHome(
                    sections = listOf(
                        HomeSectionEntry(HomeSection.RECENTLY_PLAYED, visible = true),
                        HomeSectionEntry(HomeSection.RECENTLY_ADDED, visible = true),
                        HomeSectionEntry(HomeSection.MOST_PLAYED, visible = true),
                        HomeSectionEntry(HomeSection.FAVORITES, visible = true),
                    ),
                ),
                pages = StylePages(
                    albums = defaultListLayout(),
                    artists = defaultListLayout(),
                    genres = defaultListLayout(),
                    songs = defaultListLayout(),
                    playlists = defaultListLayout(),
                ),
                nowPlaying = StyleNowPlaying(
                    compact = StyleCompactPlayer(
                        blocks = listOf(
                            NowPlayingBlock(NowPlayingBlockKind.ARTWORK),
                            NowPlayingBlock(NowPlayingBlockKind.TITLE),
                            NowPlayingBlock(NowPlayingBlockKind.SUBTITLE),
                            NowPlayingBlock(NowPlayingBlockKind.CONTROLS),
                            NowPlayingBlock(NowPlayingBlockKind.PROGRESS),
                        ),
                        buttons = listOf(
                            StyleControlButton(PlayerAction.PREVIOUS),
                            StyleControlButton(PlayerAction.PLAY_PAUSE),
                            StyleControlButton(PlayerAction.NEXT),
                        ),
                        text = StyleTextBlocks(
                            title = StyleTextBlock(template = "%title%"),
                            subtitle = StyleTextBlock(template = "%artist%"),
                        ),
                        progress = StyleProgress(
                            appearance = ProgressAppearance.HAIRLINE,
                            heightDp = 2f,
                            showRemainingTime = false,
                        ),
                    ),
                    expanded = OrientationPair(
                        portrait = defaultExpandedPlayer(),
                        landscape = defaultExpandedPlayer(),
                    ),
                    background = null,
                ),
            ),
        )
    }

    private fun defaultListLayout(): StyleListLayout = StyleListLayout(
        columns = OrientationPair(portrait = 3, landscape = 5),
        spacingDp = OrientationPair(portrait = 8f, landscape = 8f),
    )

    private fun defaultExpandedPlayer(): StyleExpandedPlayer = StyleExpandedPlayer(
        blocks = listOf(
            NowPlayingBlock(NowPlayingBlockKind.ARTWORK),
            NowPlayingBlock(NowPlayingBlockKind.TITLE),
            NowPlayingBlock(NowPlayingBlockKind.SUBTITLE),
            NowPlayingBlock(NowPlayingBlockKind.PROGRESS),
            NowPlayingBlock(NowPlayingBlockKind.CONTROLS),
            NowPlayingBlock(NowPlayingBlockKind.QUEUE),
            NowPlayingBlock(NowPlayingBlockKind.LYRICS),
        ),
        buttons = listOf(
            StyleControlButton(PlayerAction.SHUFFLE),
            StyleControlButton(PlayerAction.PREVIOUS),
            StyleControlButton(
                action = PlayerAction.PLAY_PAUSE,
                style = ControlButtonStyle.FILLED,
                size = ControlButtonSize.LARGE,
            ),
            StyleControlButton(PlayerAction.NEXT),
            StyleControlButton(PlayerAction.REPEAT),
        ),
        text = StyleTextBlocks(
            title = StyleTextBlock(
                template = "%title%",
                alignment = TextAlignment.CENTER,
                maxLines = 2,
            ),
            subtitle = StyleTextBlock(
                template = "%artist%",
                alignment = TextAlignment.CENTER,
                maxLines = 1,
            ),
        ),
        progress = StyleProgress(
            appearance = ProgressAppearance.SLIDER,
            heightDp = 4f,
            showRemainingTime = true,
        ),
    )

    @Test
    fun `the envelope schema version is pinned at v1`() {
        assertThat(StyleDocument.CURRENT_SCHEMA_VERSION).isEqualTo(1)
        assertThat(StyleDocument(id = "id", name = "name").schemaVersion).isEqualTo(1)
    }

    @Test
    fun `document equality is structural`() {
        val first = StyleDocument(id = "id", name = "name")
        val second = StyleDocument(id = "id", name = "name")
        val changed = first.copy(app = first.app.copy(darkMode = DarkMode.DARK))

        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
        assertThat(changed).isNotEqualTo(first)
        assertThat(changed.app.darkMode).isEqualTo(DarkMode.DARK)
    }

    @Test
    fun `copy leaves the original document untouched`() {
        val original = StyleDocument(id = "id", name = "name")
        val background = StyleBackdrop(layers = listOf(StyleSolidBackdropLayer()))
        val copied = original.copy(
            name = "Other name",
            nowPlaying = original.nowPlaying.copy(background = background),
        )

        assertThat(original.name).isEqualTo("name")
        assertThat(original.nowPlaying.background).isNull()
        assertThat(copied.name).isEqualTo("Other name")
        assertThat(copied.nowPlaying.background).isEqualTo(background)
    }

    @Test
    fun `every model field is read-only`() {
        StyleModelSurface.modelTypes.forEach { type ->
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    assertWithMessage("${type.simpleName}.${field.name} is final")
                        .that(Modifier.isFinal(field.modifiers))
                        .isTrue()
                }
        }
    }

    @Test
    fun `app defaults mirror the Phase 5 style tokens`() {
        val app = StyleApp()

        assertThat(app.colorSource).isEqualTo(ColorSource.SYSTEM)
        assertThat(app.seedColor).isEqualTo("#7CC4FF")
        assertThat(app.darkMode).isEqualTo(DarkMode.SYSTEM)
        assertThat(app.colorPalette).isEqualTo(ColorPalette.TONAL_SPOT)
        assertThat(app.colorContrast).isEqualTo(ColorContrast.STANDARD)
        assertThat(app.cornerScale).isEqualTo(CornerRoundness.DEFAULT)
        assertThat(app.fontFamilies).isEqualTo(
            StyleFontFamilies(title = null, body = null, lyrics = null, nav = null),
        )
        assertThat(app.effects).isEqualTo(
            StyleEffects(
                quality = QualityTier.AUTO,
                progressiveBlur = ProgressiveBlur.AUTO,
                overlayAlpha = 0.4f,
                legibilityShield = true,
                artColorFollowing = false,
                audioReactive = false,
                tiltParallax = false,
                tiltParallaxMaxOffsetDp = 12f,
            ),
        )
    }
}
