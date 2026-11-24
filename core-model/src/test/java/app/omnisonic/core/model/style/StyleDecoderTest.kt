package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StyleDecoderTest {

    private fun document(
        schemaVersion: String? = "1",
        id: String? = "\"style-id\"",
        name: String? = "\"Style name\"",
        app: String? = "{}",
        home: String? = "{}",
        pages: String? = "{}",
        nowPlaying: String? = "{}",
    ): String {
        val fields = buildList {
            schemaVersion?.let { add("\"schemaVersion\":$it") }
            id?.let { add("\"id\":$it") }
            name?.let { add("\"name\":$it") }
            app?.let { add("\"app\":$it") }
            home?.let { add("\"home\":$it") }
            pages?.let { add("\"pages\":$it") }
            nowPlaying?.let { add("\"nowPlaying\":$it") }
        }
        return fields.joinToString(separator = ",", prefix = "{", postfix = "}")
    }

    private fun accepted(json: String): StyleDecodeResult.Decoded =
        when (val result = StyleDecoder.decode(json)) {
            is StyleDecodeResult.Decoded -> result
            is StyleDecodeResult.Rejected ->
                throw AssertionError("expected a decoded document, got ${result.errors.map { it.message }}")
        }

    private fun rejected(json: String): StyleDecodeResult.Rejected =
        when (val result = StyleDecoder.decode(json)) {
            is StyleDecodeResult.Rejected -> result
            is StyleDecodeResult.Decoded -> throw AssertionError("expected a rejection, got ${result.document}")
        }

    @Test
    fun `a minimal valid document decodes to the documented defaults`() {
        val decoded = accepted(document())

        assertThat(decoded.document).isEqualTo(StyleDocument(id = "style-id", name = "Style name"))
        assertThat(decoded.warnings).isEmpty()
    }

    @Test
    fun `a full document decodes every section`() {
        val json = """
            {
              "schemaVersion": 1,
              "id": "style-full",
              "name": "Full style",
              "app": {
                "colorSource": "SEED",
                "seedColor": "#112233",
                "darkMode": "BLACK",
                "colorPalette": "VIBRANT",
                "colorContrast": "HIGH",
                "cornerScale": "LARGE",
                "fontFamilies": {
                  "title": "Heading Face",
                  "body": null,
                  "lyrics": "Lyric Face",
                  "nav": "Nav Face"
                },
                "effects": {
                  "quality": "HIGH",
                  "progressiveBlur": "OFF",
                  "overlayAlpha": 0.25,
                  "legibilityShield": false,
                  "artColorFollowing": true,
                  "audioReactive": true,
                  "tiltParallax": true,
                  "tiltParallaxMaxOffsetDp": 20
                }
              },
              "home": {
                "sections": [
                  {"section": "MOST_PLAYED"},
                  {"section": "FAVORITES", "visible": false}
                ]
              },
              "pages": {
                "albums": {
                  "columns": {"portrait": 2, "landscape": 4},
                  "spacingDp": {"portrait": 4, "landscape": 12}
                }
              },
              "nowPlaying": {
                "compact": {
                  "blocks": [{"kind": "ARTWORK"}, {"kind": "CONTROLS", "visible": false}],
                  "buttons": [
                    {"action": "PREVIOUS"},
                    {"action": "PLAY_PAUSE", "style": "FILLED", "size": "LARGE"}
                  ],
                  "text": {
                    "title": {"template": "%title%", "alignment": "CENTER", "maxLines": 2},
                    "subtitle": {"template": "%artist%", "alignment": "END"}
                  },
                  "progress": {"appearance": "HAIRLINE", "heightDp": 1, "showRemainingTime": true}
                },
                "expanded": {
                  "portrait": {"blocks": [{"kind": "ARTWORK"}]},
                  "landscape": {"blocks": [{"kind": "QUEUE"}]}
                },
                "background": {
                  "extent": "FULL_SCREEN",
                  "layers": [
                    {"kind": "SOLID", "color": "SCRIM", "alpha": 0.5},
                    {"kind": "GRADIENT", "anchor": "TOP_AND_BOTTOM", "artTint": 0.25, "blend": "SCREEN"},
                    {"kind": "ALBUM_ART", "scale": 1.5},
                    {"kind": "BLUR", "radiusDp": 32, "extent": "FULL_SCREEN"},
                    {"kind": "GRAIN", "density": 0.25},
                    {"kind": "VIGNETTE", "strength": 0.75},
                    {"kind": "GRADIENT_MESH", "intensity": 0.5}
                  ]
                }
              }
            }
        """.trimIndent()

        val expected = StyleDocument(
            id = "style-full",
            name = "Full style",
            app = StyleApp(
                colorSource = ColorSource.SEED,
                seedColor = "#112233",
                darkMode = DarkMode.BLACK,
                colorPalette = ColorPalette.VIBRANT,
                colorContrast = ColorContrast.HIGH,
                cornerScale = CornerRoundness.LARGE,
                fontFamilies = StyleFontFamilies(
                    title = "Heading Face",
                    body = null,
                    lyrics = "Lyric Face",
                    nav = "Nav Face",
                ),
                effects = StyleEffects(
                    quality = QualityTier.HIGH,
                    progressiveBlur = ProgressiveBlur.OFF,
                    overlayAlpha = 0.25f,
                    legibilityShield = false,
                    artColorFollowing = true,
                    audioReactive = true,
                    tiltParallax = true,
                    tiltParallaxMaxOffsetDp = 20f,
                ),
            ),
            home = StyleHome(
                sections = listOf(
                    HomeSectionEntry(HomeSection.MOST_PLAYED),
                    HomeSectionEntry(HomeSection.FAVORITES, visible = false),
                ),
            ),
            pages = StylePages(
                albums = StyleListLayout(
                    columns = OrientationPair(portrait = 2, landscape = 4),
                    spacingDp = OrientationPair(portrait = 4f, landscape = 12f),
                ),
            ),
            nowPlaying = StyleNowPlaying(
                compact = StyleCompactPlayer(
                    blocks = listOf(
                        NowPlayingBlock(NowPlayingBlockKind.ARTWORK),
                        NowPlayingBlock(NowPlayingBlockKind.CONTROLS, visible = false),
                    ),
                    buttons = listOf(
                        StyleControlButton(PlayerAction.PREVIOUS),
                        StyleControlButton(
                            action = PlayerAction.PLAY_PAUSE,
                            style = ControlButtonStyle.FILLED,
                            size = ControlButtonSize.LARGE,
                        ),
                    ),
                    text = StyleTextBlocks(
                        title = StyleTextBlock(
                            template = "%title%",
                            alignment = TextAlignment.CENTER,
                            maxLines = 2,
                        ),
                        subtitle = StyleTextBlock(template = "%artist%", alignment = TextAlignment.END),
                    ),
                    progress = StyleProgress(
                        appearance = ProgressAppearance.HAIRLINE,
                        heightDp = 1f,
                        showRemainingTime = true,
                    ),
                ),
                expanded = OrientationPair(
                    portrait = StyleExpandedPlayer(blocks = listOf(NowPlayingBlock(NowPlayingBlockKind.ARTWORK))),
                    landscape = StyleExpandedPlayer(blocks = listOf(NowPlayingBlock(NowPlayingBlockKind.QUEUE))),
                ),
                background = StyleBackdrop(
                    extent = BackdropExtent.FULL_SCREEN,
                    layers = listOf(
                        StyleSolidBackdropLayer(color = BackdropColor.SCRIM, alpha = 0.5f),
                        StyleGradientBackdropLayer(
                            anchor = BackdropAnchor.TOP_AND_BOTTOM,
                            artTint = 0.25f,
                            blend = BackdropBlendMode.SCREEN,
                        ),
                        StyleAlbumArtBackdropLayer(scale = 1.5f),
                        StyleBlurBackdropLayer(radiusDp = 32f, extent = BackdropBlurExtent.FULL_SCREEN),
                        StyleGrainBackdropLayer(density = 0.25f),
                        StyleVignetteBackdropLayer(strength = 0.75f),
                        StyleGradientMeshBackdropLayer(intensity = 0.5f),
                    ),
                ),
            ),
        )

        val decoded = accepted(json)

        assertThat(decoded.document).isEqualTo(expected)
        assertThat(decoded.warnings).isEmpty()
    }

    @Test
    fun `unknown keys at every depth warn with their path and the parse still succeeds`() {
        val rootWithExtraKey = """
            {"schemaVersion":1,"id":"style-id","name":"Style name","app":{},"home":{},"pages":{},"nowPlaying":{},"tokens":{}}
        """.trimIndent()
        val cases = listOf(
            rootWithExtraKey to "tokens",
            document(app = """{"mystery":1}""") to "app.mystery",
            document(app = """{"effects":{"mystery":1}}""") to "app.effects.mystery",
            document(home = """{"sections":[{"mystery":1}]}""") to "home.sections[0].mystery",
            document(nowPlaying = """{"expanded":{"portrait":{"mystery":1},"landscape":{}}}""") to
                "nowPlaying.expanded.portrait.mystery",
            document(nowPlaying = """{"background":{"layers":[{"kind":"SOLID","mystery":1}]}}""") to
                "nowPlaying.background.layers[0].mystery",
        )

        cases.forEach { (json, path) ->
            val decoded = accepted(json)

            assertThat(decoded.warnings.map { it.path }).containsExactly(path)
            assertThat(decoded.warnings.single().message).isEqualTo("$path: unknown key dropped")
        }
    }

    @Test
    fun `unknown keys are dropped from the decoded document`() {
        val decoded = accepted(
            document(
                app = """{"mystery":true,"effects":{"mystery":"value"}}""",
                home = """{"sections":[{"mystery":{"deep":1}}]}""",
            ),
        )

        assertThat(decoded.document.app).isEqualTo(StyleApp())
        assertThat(decoded.document.home).isEqualTo(
            StyleHome(sections = listOf(HomeSectionEntry())),
        )
    }

    @Test
    fun `warnings never fail a parse and every warning is retained`() {
        val decoded = accepted(
            document(
                app = """{"mystery":1,"effects":{"other":2}}""",
                nowPlaying = """{"compact":{"text":{"stray":"x"}}}""",
            ),
        )

        assertThat(decoded.warnings.map { it.path }).containsExactly(
            "app.mystery",
            "app.effects.other",
            "nowPlaying.compact.text.stray",
        )
        assertThat(decoded.warnings.map { it.detail }).containsExactly(
            "unknown key dropped",
            "unknown key dropped",
            "unknown key dropped",
        )
    }

    @Test
    fun `integral JSON numbers decode as integers`() {
        val decoded = accepted(
            document(
                home = """{"sections":[]}""",
                pages = """{"songs":{"columns":{"portrait":4.0,"landscape":6.0}}}""",
                nowPlaying = """{"compact":{"text":{"title":{"maxLines":2.0}}}}""",
            ),
        )

        assertThat(decoded.document.pages.songs.columns).isEqualTo(OrientationPair(4, 6))
        assertThat(decoded.document.nowPlaying.compact.text.title.maxLines).isEqualTo(2)
    }

    @Test
    fun `an explicit null background keeps the default stack`() {
        val decoded = accepted(document(nowPlaying = """{"background":null}"""))

        assertThat(decoded.document.nowPlaying.background).isNull()
    }

    @Test
    fun `partial text blocks keep their contextual templates`() {
        val decoded = accepted(
            document(
                nowPlaying = """
                    {
                      "compact": {"text": {"title": {"alignment": "CENTER"}, "subtitle": {}}},
                      "expanded": {
                        "portrait": {"text": {"title": {"maxLines": 2}}},
                        "landscape": {"text": {"subtitle": {"alignment": "END"}}}
                      }
                    }
                """.trimIndent(),
            ),
        )

        assertThat(decoded.document.nowPlaying.compact.text.title).isEqualTo(
            StyleTextBlock(template = "%title%", alignment = TextAlignment.CENTER, maxLines = 1),
        )
        assertThat(decoded.document.nowPlaying.compact.text.subtitle).isEqualTo(
            StyleTextBlock(template = "%artist%", alignment = TextAlignment.START, maxLines = 1),
        )
        assertThat(decoded.document.nowPlaying.expanded.portrait.text.title).isEqualTo(
            StyleTextBlock(template = "%title%", alignment = TextAlignment.CENTER, maxLines = 2),
        )
        assertThat(decoded.document.nowPlaying.expanded.landscape.text.subtitle).isEqualTo(
            StyleTextBlock(template = "%artist%", alignment = TextAlignment.END, maxLines = 1),
        )
    }

    @Test
    fun `a supplied template replaces only the contextual default text`() {
        val decoded = accepted(
            document(
                nowPlaying = """
                    {
                      "compact": {"text": {"title": {"template": "%album%"}}},
                      "expanded": {
                        "portrait": {"text": {"title": {"template": "%album%"}}},
                        "landscape": {"text": {"subtitle": {"template": "%album%"}}}
                      }
                    }
                """.trimIndent(),
            ),
        )

        assertThat(decoded.document.nowPlaying.compact.text.title).isEqualTo(
            StyleTextBlock(template = "%album%"),
        )
        assertThat(decoded.document.nowPlaying.compact.text.subtitle).isEqualTo(
            StyleTextBlock(template = "%artist%"),
        )
        assertThat(decoded.document.nowPlaying.expanded.portrait.text.title.template).isEqualTo("%album%")
        assertThat(decoded.document.nowPlaying.expanded.landscape.text.subtitle.template).isEqualTo("%album%")
    }

    @Test
    fun `malformed JSON is rejected at the root path`() {
        val rejected = rejected("""{"schemaVersion":1,"id":}""")

        assertThat(rejected.firstMessage).startsWith("\$: malformed JSON")
    }

    @Test
    fun `trailing content after the document is rejected`() {
        val rejected = rejected(document() + " {}")

        assertThat(rejected.firstMessage).startsWith("\$: malformed JSON")
    }

    @Test
    fun `a non-object root is rejected with its type`() {
        assertThat(rejected("[1,2,3]").firstMessage).isEqualTo("\$: expected object, got array")
        assertThat(rejected("null").firstMessage).isEqualTo("\$: expected object, got null")
    }
}
