package app.omnisonic.core.data.style

import app.omnisonic.core.model.style.ColorContrast
import app.omnisonic.core.model.style.ColorPalette
import app.omnisonic.core.model.style.ColorSource
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.DarkMode
import app.omnisonic.core.model.style.HomeSection
import app.omnisonic.core.model.style.HomeSectionEntry
import app.omnisonic.core.model.style.OrientationPair
import app.omnisonic.core.model.style.PlayerAction
import app.omnisonic.core.model.style.StyleApp
import app.omnisonic.core.model.style.StyleControlButton
import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleExpandedPlayer
import app.omnisonic.core.model.style.StyleFontFamilies
import app.omnisonic.core.model.style.StyleHome
import app.omnisonic.core.model.style.StyleIssue
import app.omnisonic.core.model.style.StyleNowPlaying
import app.omnisonic.core.model.style.StylePages
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import app.omnisonic.core.model.style.StyleNormalizer
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class StyleConverterTest {

    private val treeAdapter = Moshi.Builder().build().adapter(Any::class.java)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/style-samples/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    private fun container(
        style: String,
        type: String = "application",
        id: String = "test",
        name: String = "Test",
    ): String = treeAdapter.toJson(
        mapOf(
            "name" to name,
            "type" to type,
            "id" to id,
            "imported" to true,
            "version" to 1,
            "style" to style,
        ),
    )

    private fun nowPlayingContainer(config: String): String =
        container(
            """{"i":{"now.playing.page.configuration":${treeAdapter.toJson(config)}}}""",
            type = "nowplaying",
        )

    private fun converted(result: StyleConversionResult): StyleConversionResult.Converted =
        result as StyleConversionResult.Converted

    private fun rejected(result: StyleConversionResult): StyleConversionResult.Rejected =
        result as StyleConversionResult.Rejected

    private fun warnings(result: StyleConversionResult): List<StyleIssue> = converted(result).warnings

    private fun warningPaths(result: StyleConversionResult): List<String> = warnings(result).map { it.path }

    @ParameterizedTest
    @MethodSource("samples")
    fun `every sample converts to a valid v1 document`(name: String) {
        val result = converted(StyleConverter.convert(fixture(name)))

        assertThat(result.document.schemaVersion).isEqualTo(1)
        assertThat(result.document.id).isNotEmpty()
        assertThat(result.document.name).isNotEmpty()
        val written = StyleWriter.write(result.document) as StyleWriteResult.Written
        val decoded = StyleDecoder.decode(written.text)
        assertThat(decoded).isInstanceOf(StyleDecodeResult.Decoded::class.java)
    }

    @ParameterizedTest
    @MethodSource("samples")
    fun `each sample reports unmapped keys as warnings`(name: String) {
        val result = StyleConverter.convert(fixture(name))

        assertThat(warnings(result)).isNotEmpty()
        assertThat(warningPaths(result).all { it.startsWith("style.") }).isTrue()
    }

    @Test
    fun `application sample maps evidenced app values`() {
        val document = converted(StyleConverter.convert(fixture("application-a.json"))).document

        assertThat(document.app.colorSource).isEqualTo(ColorSource.SEED)
        assertThat(document.app.darkMode).isEqualTo(DarkMode.SYSTEM)
        assertThat(document.app.colorPalette).isEqualTo(ColorPalette.NEUTRAL)
        assertThat(document.app.colorContrast).isEqualTo(ColorContrast.HIGH)
        assertThat(document.app.cornerScale).isEqualTo(CornerRoundness.LARGE)
        assertThat(document.app.seedColor).isEqualTo("#FF00FF99")
        assertThat(document.app.fontFamilies).isEqualTo(
            StyleFontFamilies(title = "Inter", body = "Inter", lyrics = "Inter", nav = "Inter"),
        )
    }

    @Test
    fun `application sample maps per-list column and spacing pairs`() {
        val pages = converted(StyleConverter.convert(fixture("application-a.json"))).document.pages

        assertThat(pages.albums.columns).isEqualTo(OrientationPair(portrait = 2, landscape = 5))
        assertThat(pages.albums.spacingDp).isEqualTo(OrientationPair(portrait = 11f, landscape = 4f))
        assertThat(pages.artists.columns).isEqualTo(OrientationPair(portrait = 2, landscape = 5))
        assertThat(pages.artists.spacingDp).isEqualTo(OrientationPair(portrait = 5f, landscape = 4f))
        assertThat(pages.genres.columns).isEqualTo(OrientationPair(portrait = 2, landscape = 5))
        assertThat(pages.songs.columns).isEqualTo(OrientationPair(portrait = 3, landscape = 5))
        assertThat(pages.playlists.columns).isEqualTo(OrientationPair(portrait = 3, landscape = 5))
        assertThat(pages.playlists.spacingDp).isEqualTo(OrientationPair(portrait = 6f, landscape = 4f))
    }

    @Test
    fun `home rows map by kind in first-seen order`() {
        val home = converted(StyleConverter.convert(fixture("application-a.json"))).document.home

        assertThat(home.sections).containsExactly(
            HomeSectionEntry(HomeSection.FAVORITES, visible = true),
            HomeSectionEntry(HomeSection.RECENTLY_PLAYED, visible = true),
            HomeSectionEntry(HomeSection.RECENTLY_ADDED, visible = true),
            HomeSectionEntry(HomeSection.MOST_PLAYED, visible = true),
        ).inOrder()
    }

    @Test
    fun `hidden home rows become invisible sections`() {
        val input = container("""{"i":{"home.rows":"q:recentalbums","home.rows.hidden":"q:favoritealbums"}}""")

        val home = converted(StyleConverter.convert(input)).document.home

        assertThat(home.sections).containsExactly(
            HomeSectionEntry(HomeSection.RECENTLY_ADDED, visible = true),
            HomeSectionEntry(HomeSection.FAVORITES, visible = false),
        ).inOrder()
    }

    @Test
    fun `now-playing sample maps compact text and buttons`() {
        val document = converted(StyleConverter.convert(fixture("now-playing.json"))).document

        assertThat(document.nowPlaying.compact.text.title.template).isEqualTo("%title%")
        assertThat(document.nowPlaying.compact.text.subtitle.template).isEqualTo("%artist%")
        assertThat(document.nowPlaying.compact.buttons.map { it.action })
            .containsExactly(PlayerAction.FAVORITE, PlayerAction.PLAY_PAUSE)
            .inOrder()
        assertThat(document.nowPlaying.expanded.portrait.buttons.map { it.action })
            .containsExactly(PlayerAction.PREVIOUS, PlayerAction.PLAY_PAUSE, PlayerAction.NEXT)
            .inOrder()
        assertThat(document.app).isEqualTo(StyleApp())
        assertThat(document.home).isEqualTo(StyleHome())
        assertThat(document.pages).isEqualTo(StylePages())
    }

    @Test
    fun `ordinal values with unknown meaning are warned and not guessed`() {
        val result = StyleConverter.convert(fixture("application-a.json"))
        val paths = warningPaths(result)

        assertThat(paths).contains("style.d.albums.list.display")
        assertThat(paths).contains("style.d.nav.type.portrait")
        assertThat(paths).contains("style.i.now.playing.page.configuration.compactConfiguration.progressStyle")
        assertThat(warnings(result).first { it.path == "style.d.albums.list.display" }.detail)
            .contains("ordinal")
        assertThat(converted(result).document.pages.albums.columns)
            .isEqualTo(OrientationPair(portrait = 2, landscape = 5))
    }

    @Test
    fun `an unknown enum token warns and keeps the default`() {
        val input = container(
            """{"a":{"theme.color.palette":"Bogus","theme.color.contrast":"weird","dark.mode":"sepia","theme.color.source":"unknown","theme.corner.size":9}}""",
        )

        val result = StyleConverter.convert(input)
        val document = converted(result).document

        assertThat(document.app.colorPalette).isEqualTo(ColorPalette.TONAL_SPOT)
        assertThat(document.app.colorContrast).isEqualTo(ColorContrast.STANDARD)
        assertThat(document.app.darkMode).isEqualTo(DarkMode.SYSTEM)
        assertThat(document.app.colorSource).isEqualTo(ColorSource.SYSTEM)
        assertThat(document.app.cornerScale).isEqualTo(CornerRoundness.DEFAULT)
        assertThat(warningPaths(result)).containsAtLeast(
            "style.a.theme.color.palette",
            "style.a.theme.color.contrast",
            "style.a.dark.mode",
            "style.a.theme.color.source",
            "style.a.theme.corner.size",
        )
    }

    @Test
    fun `an unknown control action ordinal warns instead of mapping`() {
        val config = """
            {"compactConfiguration":{},"portraitConfiguration":{"controlButton1":{"action":13,"style":0,"size":0},"controlButton2":{"action":3,"style":0,"size":0}},"landscapeConfiguration":{}}
        """.trimIndent()

        val result = StyleConverter.convert(nowPlayingContainer(config))
        val document = converted(result).document

        assertThat(document.nowPlaying.expanded.portrait.buttons)
            .containsExactly(StyleControlButton(PlayerAction.PREVIOUS))
        assertThat(warningPaths(result)).contains(
            "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.action",
        )
    }

    @Test
    fun `the embedded image and absolute paths are dropped with warnings`() {
        val result = StyleConverter.convert(fixture("application-a.json"))
        val document = converted(result).document
        val paths = warningPaths(result)

        assertThat(paths).contains("style.ci")
        assertThat(paths).contains("style.i.home.custom.icon")
        assertThat(warnings(result).first { it.path == "style.i.home.custom.icon" }.detail)
            .contains("absolute path")
        val written = (StyleWriter.write(document) as StyleWriteResult.Written).text
        assertThat(written).doesNotContain("iVBORw0KGgo")
        assertThat(written).doesNotContain("/srv/omnisonic-fixtures")
    }

    @Test
    fun `a template with an unsupported placeholder keeps the default and warns`() {
        val result = StyleConverter.convert(fixture("application-a.json"))
        val document = converted(result).document
        val path = "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customText"

        assertThat(document.nowPlaying.compact.text.subtitle.template).isEqualTo("%artist%")
        assertThat(warningPaths(result)).contains(path)
        val detail = warnings(result).first { it.path == path }.detail
        assertThat(detail).contains("%player.remaining%")
        assertThat(detail).contains("not supported")
    }

    @Test
    fun `a supported template is carried over without a template warning`() {
        val config = """
            {"compactConfiguration":{"title":{"customText":"%album% - %artist%","lineCount":2}}}
        """.trimIndent()

        val result = StyleConverter.convert(nowPlayingContainer(config))

        assertThat(converted(result).document.nowPlaying.compact.text.title.template)
            .isEqualTo("%album% - %artist%")
        assertThat(warningPaths(result)).doesNotContain(
            "style.i.now.playing.page.configuration.compactConfiguration.title.customText",
        )
    }

    @Test
    fun `hidden home rows convert even when the visible list is absent`() {
        val input = container("""{"i":{"home.rows.hidden":"q:favoritealbums"}}""")

        val home = converted(StyleConverter.convert(input)).document.home

        assertThat(home.sections).containsExactly(
            HomeSectionEntry(HomeSection.FAVORITES, visible = false),
        )
    }

    @Test
    fun `an explicitly empty visible list keeps an empty composition`() {
        val input = container("""{"i":{"home.rows":""}}""")

        val home = converted(StyleConverter.convert(input)).document.home

        assertThat(home.sections).isEmpty()
    }

    @ParameterizedTest
    @MethodSource("evidencedHomeRows")
    fun `every evidenced home row maps to its exact section`(token: String, expected: HomeSection) {
        val input = container(treeAdapter.toJson(mapOf("i" to mapOf("home.rows" to token))))

        val result = StyleConverter.convert(input)

        assertThat(converted(result).document.home.sections)
            .containsExactly(HomeSectionEntry(expected, visible = true))
        assertThat(warningPaths(result)).doesNotContain("style.i.home.rows")
    }

    @ParameterizedTest
    @MethodSource("nearPrefixHomeRows")
    fun `a near-prefix home row warns and is dropped`(token: String) {
        val input = container(treeAdapter.toJson(mapOf("i" to mapOf("home.rows" to token))))

        val result = StyleConverter.convert(input)

        assertThat(converted(result).document.home.sections).isEmpty()
        assertThat(warningPaths(result)).containsExactly("style.i.home.rows")
        val warning = warnings(result).single()
        assertThat(warning.detail).contains("'$token'")
        assertThat(warning.detail).contains("position 1")
        assertThat(warning.detail).contains("not carried over")
    }

    @ParameterizedTest
    @MethodSource("malformedHomeRows")
    fun `a malformed home row warns and is dropped`(token: String) {
        val input = container(treeAdapter.toJson(mapOf("i" to mapOf("home.rows" to token))))

        val result = StyleConverter.convert(input)

        assertThat(converted(result).document.home.sections).isEmpty()
        assertThat(warningPaths(result)).containsExactly("style.i.home.rows")
        assertThat(warnings(result).single().detail).contains("'$token'")
    }

    @Test
    fun `a near-prefix token cannot stand in for an exact family`() {
        val input = container("""{"i":{"home.rows":"q:recentzzz;q:recentalbums"}}""")

        val result = StyleConverter.convert(input)

        assertThat(converted(result).document.home.sections).containsExactly(
            HomeSectionEntry(HomeSection.RECENTLY_ADDED, visible = true),
        )
        assertThat(warningPaths(result)).containsExactly("style.i.home.rows")
        val warning = warnings(result).single()
        assertThat(warning.detail).contains("'q:recentzzz'")
        assertThat(warning.detail).contains("position 1")
    }

    @Test
    fun `a row family collapsing more than once warns per dropped token`() {
        val input = container("""{"i":{"home.rows":"q:recentalbums;q:recentalbums;q:favoritealbums"}}""")

        val result = StyleConverter.convert(input)
        val home = converted(result).document.home

        assertThat(home.sections).containsExactly(
            HomeSectionEntry(HomeSection.RECENTLY_ADDED, visible = true),
            HomeSectionEntry(HomeSection.FAVORITES, visible = true),
        ).inOrder()
        assertThat(warningPaths(result)).contains("style.i.home.rows")
        val detail = warnings(result).first { it.path == "style.i.home.rows" }.detail
        assertThat(detail).contains("position 2")
        assertThat(detail).contains("repeats")
    }

    @Test
    fun `a path or URI font family is dropped with a warning`() {
        val values = listOf(
            "/srv/fonts/Inter.ttf",
            "C:\\Windows\\Fonts\\Inter.ttf",
            "\\\\server\\share\\Inter.ttf",
            "file:///srv/fonts/Inter.ttf",
            "content://fonts/Inter",
        )

        values.forEach { family ->
            val result = StyleConverter.convert(
                container(treeAdapter.toJson(mapOf("a" to mapOf("typography" to family)))),
            )

            assertThat(converted(result).document.app.fontFamilies)
                .isEqualTo(StyleFontFamilies())
            assertThat(warningPaths(result)).contains("style.a.typography")
            assertThat(warnings(result).first { it.path == "style.a.typography" }.detail)
                .contains("path or URI")
        }
    }

    @Test
    fun `a portable font family still maps to every role`() {
        val result = StyleConverter.convert(
            container("""{"a":{"typography":"Inter"}}"""),
        )

        assertThat(converted(result).document.app.fontFamilies).isEqualTo(
            StyleFontFamilies(title = "Inter", body = "Inter", lyrics = "Inter", nav = "Inter"),
        )
        assertThat(warningPaths(result)).doesNotContain("style.a.typography")
    }

    @Test
    fun `numbered control buttons follow their slot order, not the object order`() {
        val config = """
            {
              "compactConfiguration":{},
              "portraitConfiguration":{
                "controlButton3":{"action":4,"style":0,"size":0},
                "controlButton1":{"action":3,"style":0,"size":0},
                "controlButton2":{"action":1,"style":0,"size":0}
              },
              "landscapeConfiguration":{}
            }
        """.trimIndent()

        val document = converted(StyleConverter.convert(nowPlayingContainer(config))).document

        assertThat(document.nowPlaying.expanded.portrait.buttons.map { it.action })
            .containsExactly(PlayerAction.PREVIOUS, PlayerAction.PLAY_PAUSE, PlayerAction.NEXT)
            .inOrder()
    }

    @Test
    fun `malformed JSON is rejected with a readable message`() {
        val result = rejected(StyleConverter.convert("{ not json"))

        assertThat(result.firstMessage).startsWith("\$: malformed JSON:")
    }

    @Test
    fun `foreign JSON is rejected`() {
        val result = rejected(StyleConverter.convert("""{"hello":"world"}"""))

        assertThat(result.firstMessage).isEqualTo("type: required field is missing")
    }

    @Test
    fun `a container whose style has no sections is rejected`() {
        val input = container("{}")

        val result = rejected(StyleConverter.convert(input))

        assertThat(result.firstMessage).contains("no style sections found")
    }

    @Test
    fun `an unsupported container type is rejected`() {
        val input = container("""{"a":{}}""", type = "theme")

        val result = rejected(StyleConverter.convert(input))

        assertThat(result.firstMessage).isEqualTo("type: unsupported style type 'theme'")
    }

    @Test
    fun `a malformed nested configuration warns instead of rejecting`() {
        val input = nowPlayingContainer("{oops")

        val result = StyleConverter.convert(input)

        assertThat(warningPaths(result)).contains("style.i.now.playing.page.configuration")
        assertThat(converted(result).document.nowPlaying).isEqualTo(StyleNowPlaying())
    }

    @Test
    fun `an all-empty control-button family converts to no buttons`() {
        val config = """
            {"compactConfiguration":{},"portraitConfiguration":{"controlButton1":{"action":0,"style":0,"size":0},"controlButton2":{"action":0,"style":0,"size":0}},"landscapeConfiguration":{}}
        """.trimIndent()

        val result = StyleConverter.convert(nowPlayingContainer(config))
        val document = converted(result).document
        val written = StyleWriter.write(document) as StyleWriteResult.Written

        assertThat(document.nowPlaying.expanded.portrait.buttons).isEmpty()
        assertThat(document.nowPlaying.expanded.landscape.buttons)
            .isEqualTo(StyleExpandedPlayer.DEFAULT_BUTTONS)
        assertThat(StyleDecoder.decode(written.text)).isInstanceOf(StyleDecodeResult.Decoded::class.java)
    }

    @Test
    fun `a non-integral seed is warned and keeps the default`() {
        val result = StyleConverter.convert(container("""{"a":{"theme.color.seed":1.5}}"""))

        assertThat(converted(result).document.app.seedColor).isEqualTo(StyleApp.DEFAULT_SEED_COLOR)
        assertThat(warningPaths(result)).contains("style.a.theme.color.seed")
    }

    @Test
    fun `conversion is deterministic`() {
        val first = converted(StyleConverter.convert(fixture("application-a.json")))
        val second = converted(StyleConverter.convert(fixture("application-a.json")))

        assertThat(second.document).isEqualTo(first.document)
        assertThat(second.warnings).isEqualTo(first.warnings)
    }

    @Test
    fun `converted output round-trips byte-equal after normalization`() {
        val document = converted(StyleConverter.convert(fixture("application-a.json"))).document
        val first = (StyleWriter.write(document) as StyleWriteResult.Written).text
        val normalized = (StyleNormalizer.normalize(first) as StyleWriteResult.Written).text
        val again = (StyleNormalizer.normalize(normalized) as StyleWriteResult.Written).text

        assertThat(normalized).isEqualTo(first)
        assertThat(again).isEqualTo(normalized)
    }

    @ParameterizedTest
    @MethodSource("samples")
    fun `each sample warns in a stable path order`(name: String) {
        val result = StyleConverter.convert(fixture(name))

        assertThat(warningPaths(result))
            .containsExactlyElementsIn(EXPECTED_WARNINGS.getValue(name))
            .inOrder()
    }

    companion object {

        @JvmStatic
        fun samples(): List<Arguments> = listOf(
            Arguments.of("application-a.json"),
            Arguments.of("application-b.json"),
            Arguments.of("now-playing.json"),
        )

        @JvmStatic
        fun evidencedHomeRows(): List<Arguments> = listOf(
            Arguments.of("q:favoriteplaylists", HomeSection.FAVORITES),
            Arguments.of("q:favoriteartists", HomeSection.FAVORITES),
            Arguments.of("q:favoriteradios", HomeSection.FAVORITES),
            Arguments.of("q:favoritealbums", HomeSection.FAVORITES),
            Arguments.of("q:favoritesongs", HomeSection.FAVORITES),
            Arguments.of("q:lastplayedalbums", HomeSection.RECENTLY_PLAYED),
            Arguments.of("q:lastplayedsongs", HomeSection.RECENTLY_PLAYED),
            Arguments.of("q:lastplayedartists", HomeSection.RECENTLY_PLAYED),
            Arguments.of("q:lastplayedalbumartists", HomeSection.RECENTLY_PLAYED),
            Arguments.of("q:recentalbums", HomeSection.RECENTLY_ADDED),
            Arguments.of("q:recentartists", HomeSection.RECENTLY_ADDED),
            Arguments.of("q:recentsongs", HomeSection.RECENTLY_ADDED),
            Arguments.of("q:recentreleasealbum", HomeSection.RECENTLY_ADDED),
            Arguments.of("q:recentalbumsartists", HomeSection.RECENTLY_ADDED),
            Arguments.of("q:mostplayedalbumartists", HomeSection.MOST_PLAYED),
            Arguments.of("q:mostplayedsongs", HomeSection.MOST_PLAYED),
            Arguments.of("q:mostplayedartists", HomeSection.MOST_PLAYED),
            Arguments.of("q:mostplayedalbums", HomeSection.MOST_PLAYED),
        )

        @JvmStatic
        fun nearPrefixHomeRows(): List<String> = listOf(
            "q:lastplayed",
            "q:lastplayedalbumz",
            "q:lastplayedalbums2",
            "q:recent",
            "q:recentzzz",
            "q:recentalbumsdeluxe",
            "q:mostplayed",
            "q:mostplayedalbumz",
            "q:mostplayedalbumartist",
            "q:favorite",
            "q:favorites",
            "q:favoriteplaylistz",
            "q:favoritealbumsdeluxe",
        )

        @JvmStatic
        fun malformedHomeRows(): List<String> = listOf(
            "q:",
            "q",
            ":favoritealbums",
            "q:favoritealbums:",
            "Q:favoritealbums",
            "q:FAVORITEALBUMS",
            "q:favorite albums",
            "q:favorite\u0000albums",
        )

        private val EXPECTED_WARNINGS: Map<String, List<String>> = mapOf(
            "application-a.json" to listOf(
                "style.i.app.icon",
                "style.i.widget.hide.cast.button",
                "style.i.widget.hide.filter.button",
                "style.i.widget.hide.home.button",
                "style.i.home.custom.icon",
                "style.i.home.custom.icon.banner",
                "style.i.home.icon",
                "style.i.home.icon.large",
                "style.i.home.icon.left.align",
                "style.i.home.icon.small",
                "style.i.home.icon.size",
                "style.i.tint.home.icon",
                "style.i.home.shortcuts",
                "style.i.home.shortcuts2",
                "style.i.home.shortcuts3",
                "style.i.home.shortcuts4",
                "style.i.library.page.configuration",
                "style.i.library.shortcuts",
                "style.i.main.tabs",
                "style.i.now.playing.page.configuration.compactConfiguration.size",
                "style.i.now.playing.page.configuration.compactConfiguration.floating",
                "style.i.now.playing.page.configuration.compactConfiguration.floatingProtection",
                "style.i.now.playing.page.configuration.compactConfiguration.floatingShadow",
                "style.i.now.playing.page.configuration.compactConfiguration.playerMargins",
                "style.i.now.playing.page.configuration.compactConfiguration.playerCornerRadius",
                "style.i.now.playing.page.configuration.compactConfiguration.leftHanded",
                "style.i.now.playing.page.configuration.compactConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailRoundedCorners",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailClickPlayPause",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailPauseButton",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailPreferAnimated",
                "style.i.now.playing.page.configuration.compactConfiguration.progressStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.progressColor",
                "style.i.now.playing.page.configuration.compactConfiguration.progressNoBuffer",
                "style.i.now.playing.page.configuration.compactConfiguration.progressInvertColors",
                "style.i.now.playing.page.configuration.compactConfiguration.swipeToSkip",
                "style.i.now.playing.page.configuration.compactConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.compactConfiguration.title.text",
                "style.i.now.playing.page.configuration.compactConfiguration.title.style",
                "style.i.now.playing.page.configuration.compactConfiguration.title.size",
                "style.i.now.playing.page.configuration.compactConfiguration.title.position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.verticalPosition",
                "style.i.now.playing.page.configuration.compactConfiguration.title.color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.textProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.textTransparencyWhenPlaying",
                "style.i.now.playing.page.configuration.compactConfiguration.title.margins",
                "style.i.now.playing.page.configuration.compactConfiguration.title.ellipsis",
                "style.i.now.playing.page.configuration.compactConfiguration.title.customTextClick",
                "style.i.now.playing.page.configuration.compactConfiguration.title.customTextLongClick",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Size",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Size",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.text",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.style",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.verticalPosition",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.textProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.textTransparencyWhenPlaying",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.margins",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.ellipsis",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.lineCount",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customText",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customTextClick",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customTextLongClick",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Size",
                "style.i.now.playing.page.configuration.compactConfiguration.buttonsColor",
                "style.i.now.playing.page.configuration.compactConfiguration.backSkipAmount",
                "style.i.now.playing.page.configuration.compactConfiguration.forwardSkipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.ignoreStatusBar",
                "style.i.now.playing.page.configuration.portraitConfiguration.invertTopAndBottomBars",
                "style.i.now.playing.page.configuration.portraitConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.invertedOverlayColors",
                "style.i.now.playing.page.configuration.portraitConfiguration.blackOverlayColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundCoverArtPreferAnimated",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundBlurLevel",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundProtectionBlack",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundGestureAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLeftOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeRightOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarTitle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarSubtitle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarIconSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarIconSpacingCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarShowCollapseButton",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarInvertButtonsAndCollapse",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarCollapseButtonColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton5",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton6",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton7",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarInControls",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarTextPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarTextFillSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarIconPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarIconSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton5",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton6",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton7",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarInControls",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtPreferAnimated",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtLyricsProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtRoundedCornerPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtCenterPlayIcon",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtNoCrop",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtectionCornerPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtHorizontalSwipeAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtTapAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtDoubleTapAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtLongPressAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtDropShadow",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtChangeAnimation",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverText1",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverText2",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverText3",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeNowPlayingQueuePosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeTopBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeBottomBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeCoverArtPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeControlsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeQueuePosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeQueueMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLyricsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLyricsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeCoverArtPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeControlsPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeQueuePercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLyricsPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeCoverFillSpace",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsHidden",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsRowPositioning",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.statusBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.statusBarProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.navigationBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.navigationBarProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderStyleMissingWaveForm",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderShowSpeed",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderShowTimes",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextInline",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderInvertColors",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarWidth",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarReflectionSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarReflectionIntensity",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarReflectionGap",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarIconColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarInvertColors",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText1",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText2",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText3",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText4",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText5",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText6",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsVerticalFillSpace",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ignoreStatusBar",
                "style.i.now.playing.page.configuration.landscapeConfiguration.invertTopAndBottomBars",
                "style.i.now.playing.page.configuration.landscapeConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.invertedOverlayColors",
                "style.i.now.playing.page.configuration.landscapeConfiguration.blackOverlayColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundCoverArtPreferAnimated",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundBlurLevel",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundProtectionBlack",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundGestureAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLeftOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeRightOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarTitle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarSubtitle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarIconSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarIconSpacingCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarShowCollapseButton",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarInvertButtonsAndCollapse",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarCollapseButtonColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton7",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarInControls",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarTextPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarTextFillSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarIconPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarIconSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton7",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarInControls",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtPreferAnimated",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtLyricsProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtRoundedCornerPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtCenterPlayIcon",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtNoCrop",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtectionCornerPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtHorizontalSwipeAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtTapAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtDoubleTapAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtLongPressAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtDropShadow",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtChangeAnimation",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverText1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverText2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverText3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeNowPlayingQueuePosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeTopBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeBottomBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeCoverArtPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeControlsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeQueuePosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeQueueMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLyricsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLyricsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeCoverArtPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeControlsPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeQueuePercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLyricsPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeCoverFillSpace",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsHidden",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsRowPositioning",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.statusBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.statusBarProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.navigationBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.navigationBarProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderStyleMissingWaveForm",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderShowSpeed",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderShowTimes",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextInline",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderInvertColors",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarWidth",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarReflectionSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarReflectionIntensity",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarReflectionGap",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarIconColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarInvertColors",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsVerticalFillSpace",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.style",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.overlayTransparency",
                "style.i.search.button.top.bar",
                "style.i.overview.row.s:icon",
                "style.i.overview.row.q:favoriteplaylists",
                "style.i.overview.row.pl:a-44§§Dive Right In",
                "style.i.overview.row.q:favoriteartists",
                "style.i.overview.row.pl:s-45§§Right Now",
                "style.i.overview.row.s:shortcut",
                "style.i.overview.row.pl:s-47§§by Your Favorite Artists",
                "style.i.overview.row.pl:a-13§§Rediscover",
                "style.i.overview.row.q:mostplayedalbums",
                "style.i.overview.row.pl:s-58§§Hot Dial",
                "style.i.overview.row.q:favoriteradios",
                "style.i.overview.row.pl:s-49§§Forgotten Gems",
                "style.i.overview.row.q:mostplayedalbumartists",
                "style.i.overview.row.pl:a-26§§Recently Added",
                "style.i.overview.row.q:recentalbumsartists",
                "style.i.overview.row.s:shortcut2",
                "style.i.overview.row.s:shortcut3",
                "style.i.overview.row.q:5starsongs",
                "style.i.overview.row.pl:s-16§§Daily Mix",
                "style.i.overview.row.pl:s-20§§Night Mix",
                "style.i.overview.row.s:shortcut4",
                "style.i.overview.row.q:rediscoversongs",
                "style.i.overview.row.pl:a-11§§Rediscover albums",
                "style.i.overview.row.pl:a-12§§Continue listening",
                "style.i.overview.row.q:rediscoveralbums",
                "style.i.overview.row.q:lastplayedalbums",
                "style.i.overview.row.q:lastplayedsongs",
                "style.i.overview.row.q:favoritealbums",
                "style.i.overview.row.q:recentreleasealbum",
                "style.i.overview.row.q:recentalbums",
                "style.i.overview.row.q:resumealbums",
                "style.i.overview.row.q:randomalbumartists",
                "style.i.overview.row.q:randomartists",
                "style.i.overview.row.q:favoritesongs",
                "style.i.overview.row.q:recentartists",
                "style.i.overview.row.q:randomalbums",
                "style.i.overview.row.q:mostplayedsongs",
                "style.i.overview.row.q:recentsongs",
                "style.i.overview.row.q:resumesongs",
                "style.i.overview.row.q:randomsongs",
                "style.i.overview.row.q:lastplayedartists",
                "style.i.overview.row.q:lastplayedalbumartists",
                "style.i.overview.row.q:mostplayedartists",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.a.custom.material.theme",
                "style.a.disable.color.animations",
                "style.a.theme.color.now.playing.style",
                "style.a.theme.color.np.surface",
                "style.a.theme.color.spec",
                "style.a.typography.custom",
                "style.d.album.page.configuration",
                "style.d.albums.list.display",
                "style.d.always.show.filter",
                "style.d.artist.page.configuration",
                "style.d.artists.list.display",
                "style.d.bottom.navigation.transparency",
                "style.d.floating.toolbar.full.width",
                "style.d.floating.toolbar.horizontal.padding",
                "style.d.collapsed.artist.albums",
                "style.d.collapsed.artist.songs",
                "style.d.collapsed.genre.albums",
                "style.d.collapsed.genre.artists",
                "style.d.collapsed.genre.songs",
                "style.d.field.list.0.display",
                "style.d.field.list.0.display.columns",
                "style.d.field.list.0.display.columns.landscape",
                "style.d.field.list.0.display.spacing",
                "style.d.field.list.0.display.spacing.landscape",
                "style.d.field.list.1.display",
                "style.d.field.list.1.display.columns",
                "style.d.field.list.1.display.columns.landscape",
                "style.d.field.list.1.display.spacing",
                "style.d.field.list.1.display.spacing.landscape",
                "style.d.field.list.2.display",
                "style.d.field.list.2.display.columns",
                "style.d.field.list.2.display.columns.landscape",
                "style.d.field.list.2.display.spacing",
                "style.d.field.list.2.display.spacing.landscape",
                "style.d.field.list.3.display",
                "style.d.field.list.3.display.columns",
                "style.d.field.list.3.display.columns.landscape",
                "style.d.field.list.3.display.spacing",
                "style.d.field.list.3.display.spacing.landscape",
                "style.d.field.list.4.display",
                "style.d.field.list.4.display.columns",
                "style.d.field.list.4.display.columns.landscape",
                "style.d.field.list.4.display.spacing",
                "style.d.field.list.4.display.spacing.landscape",
                "style.d.field.list.5.display",
                "style.d.field.list.5.display.columns",
                "style.d.field.list.5.display.columns.landscape",
                "style.d.field.list.5.display.spacing",
                "style.d.field.list.05.display.spacing.landscape",
                "style.d.field.list.6.display",
                "style.d.field.list.6.display.columns",
                "style.d.field.list6.display.columns.landscape",
                "style.d.field.list.6.display.spacing",
                "style.d.field.list.6.display.spacing.landscape",
                "style.d.field.list.7.display",
                "style.d.field.list.7.display.columns",
                "style.d.field.list.7.display.columns.landscape",
                "style.d.field.list.7.display.spacing",
                "style.d.field.list.7.display.spacing.landscape",
                "style.d.field.list.8.display",
                "style.d.field.list.8.display.columns",
                "style.d.field.list.8.display.columns.landscape",
                "style.d.field.list.8.display.spacing",
                "style.d.field.list.8.display.spacing.landscape",
                "style.d.field.list.9.display",
                "style.d.field.list.9.display.columns",
                "style.d.field.list.9.display.columns.landscape",
                "style.d.field.list.9.display.spacing",
                "style.d.field.list.9.display.spacing.landscape",
                "style.d.field.list.10.display",
                "style.d.field.list.10.display.columns",
                "style.d.field.list.10.display.columns.landscape",
                "style.d.field.list.10.display.spacing",
                "style.d.field.list.10.display.spacing.landscape",
                "style.d.field.list.11.display",
                "style.d.field.list.11.display.columns",
                "style.d.field.list.11.display.columns.landscape",
                "style.d.field.list.11.display.spacing",
                "style.d.field.list.11.display.spacing.landscape",
                "style.d.field.list.12.display",
                "style.d.field.list.12.display.columns",
                "style.d.field.list.12.display.columns.landscape",
                "style.d.field.list.12.display.spacing",
                "style.d.field.list.12.display.spacing.landscape",
                "style.d.field.list.13.display",
                "style.d.field.list.13.display.columns",
                "style.d.field.list.13.display.columns.landscape",
                "style.d.field.list.13.display.spacing",
                "style.d.field.list.13.display.spacing.landscape",
                "style.d.field.list.14.display",
                "style.d.field.list.14.display.columns",
                "style.d.field.list.14.display.columns.landscape",
                "style.d.field.list.14.display.spacing",
                "style.d.field.list.14.display.spacing.landscape",
                "style.d.genre.page.configuration",
                "style.d.genres.list.display",
                "style.d.header.album.thumbnail.style",
                "style.d.header.artist.thumbnail.style",
                "style.d.header.genre.thumbnail.style",
                "style.d.header.playlist.thumbnail.style",
                "style.d.hide.back.button.in.lists",
                "style.d.hide.favorite.button",
                "style.d.internet.radio.display",
                "style.d.internet.radio.display.columns",
                "style.d.internet.radio.display.columns.landscape",
                "style.d.internet.radio.display.spacing",
                "style.d.internet.radio.display.spacing.landscape",
                "style.d.inverted.sort.arrows",
                "style.d.list.hidden.button",
                "style.d.list.button.row.style",
                "style.d.list.grid.center",
                "style.d.list.text.only.single.line",
                "style.d.list.track.swipe.left.action",
                "style.d.list.track.swipe.right.action",
                "style.d.lyrics.center.text",
                "style.d.lyrics.drop.shadows",
                "style.d.lyrics.font.size.new",
                "style.d.lyrics.font.weight",
                "style.d.lyrics.hide.background.voice",
                "style.d.lyrics.hide.voice.color.indicator",
                "style.d.lyrics.hide.translations",
                "style.d.lyrics.inactive.alpha",
                "style.d.lyrics.inactive.scale",
                "style.d.lyrics.karaoke.enabled",
                "style.d.lyrics.letter.spacing",
                "style.d.lyrics.line.height",
                "style.d.lyrics.lyrics.spacing",
                "style.d.lyrics.show.close.button",
                "style.d.lyrics.show.control",
                "style.d.lyrics.show.progress",
                "style.d.lyrics.show.song.details",
                "style.d.lyrics.show.song.thumbnail.portrait",
                "style.d.lyrics.show.sync.buttons.bar",
                "style.d.lyrics.show.time.int",
                "style.d.lyrics.translations.cue",
                "style.d.lyrics.translations.colors",
                "style.d.navigation.inverted.position",
                "style.d.nav.type.landscape",
                "style.d.nav.type.portrait",
                "style.d.player.additional.info",
                "style.d.player.show.remaining",
                "style.d.player.show.track.number",
                "style.d.playlist.content.display",
                "style.d.playlist.content.display.columns",
                "style.d.playlist.content.display.columns.landscape",
                "style.d.playlist.content.display.spacing",
                "style.d.playlist.content.display.columns.spacing",
                "style.d.playlist.list.display",
                "style.d.playlist.page.configuration",
                "style.d.show.item.count",
                "style.d.show.favorite.overlay",
                "style.d.show.offline.overlay",
                "style.d.show.smart.filters.chips",
                "style.d.song.additional.info",
                "style.d.song.additional.info2",
                "style.d.songs.list.display",
                "style.ci",
            ),
            "application-b.json" to listOf(
                "style.i.app.icon",
                "style.i.widget.hide.cast.button",
                "style.i.widget.hide.filter.button",
                "style.i.widget.hide.home.button",
                "style.i.home.custom.icon",
                "style.i.home.custom.icon.banner",
                "style.i.home.icon",
                "style.i.home.icon.large",
                "style.i.home.icon.left.align",
                "style.i.home.icon.small",
                "style.i.home.icon.size",
                "style.i.tint.home.icon",
                "style.i.home.shortcuts",
                "style.i.home.shortcuts2",
                "style.i.home.shortcuts3",
                "style.i.home.shortcuts4",
                "style.i.library.page.configuration",
                "style.i.library.shortcuts",
                "style.i.main.tabs",
                "style.i.now.playing.page.configuration.compactConfiguration.size",
                "style.i.now.playing.page.configuration.compactConfiguration.floating",
                "style.i.now.playing.page.configuration.compactConfiguration.floatingProtection",
                "style.i.now.playing.page.configuration.compactConfiguration.floatingShadow",
                "style.i.now.playing.page.configuration.compactConfiguration.playerMargins",
                "style.i.now.playing.page.configuration.compactConfiguration.playerCornerRadius",
                "style.i.now.playing.page.configuration.compactConfiguration.leftHanded",
                "style.i.now.playing.page.configuration.compactConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailRoundedCorners",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailClickPlayPause",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailPauseButton",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailPreferAnimated",
                "style.i.now.playing.page.configuration.compactConfiguration.progressStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.progressColor",
                "style.i.now.playing.page.configuration.compactConfiguration.progressNoBuffer",
                "style.i.now.playing.page.configuration.compactConfiguration.progressInvertColors",
                "style.i.now.playing.page.configuration.compactConfiguration.swipeToSkip",
                "style.i.now.playing.page.configuration.compactConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.compactConfiguration.title.text",
                "style.i.now.playing.page.configuration.compactConfiguration.title.style",
                "style.i.now.playing.page.configuration.compactConfiguration.title.size",
                "style.i.now.playing.page.configuration.compactConfiguration.title.position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.verticalPosition",
                "style.i.now.playing.page.configuration.compactConfiguration.title.color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.textProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.textTransparencyWhenPlaying",
                "style.i.now.playing.page.configuration.compactConfiguration.title.margins",
                "style.i.now.playing.page.configuration.compactConfiguration.title.ellipsis",
                "style.i.now.playing.page.configuration.compactConfiguration.title.customTextClick",
                "style.i.now.playing.page.configuration.compactConfiguration.title.customTextLongClick",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Size",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Size",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.text",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.style",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.verticalPosition",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.textProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.textTransparencyWhenPlaying",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.margins",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.ellipsis",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.lineCount",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customText",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customTextClick",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customTextLongClick",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3ProtectionTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Size",
                "style.i.now.playing.page.configuration.compactConfiguration.buttonsColor",
                "style.i.now.playing.page.configuration.compactConfiguration.backSkipAmount",
                "style.i.now.playing.page.configuration.compactConfiguration.forwardSkipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.ignoreStatusBar",
                "style.i.now.playing.page.configuration.portraitConfiguration.invertTopAndBottomBars",
                "style.i.now.playing.page.configuration.portraitConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.invertedOverlayColors",
                "style.i.now.playing.page.configuration.portraitConfiguration.blackOverlayColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundCoverArtPreferAnimated",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundBlurLevel",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundProtectionBlack",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundGestureAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLeftOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeRightOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarTitle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarSubtitle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarIconSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarIconSpacingCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarShowCollapseButton",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarInvertButtonsAndCollapse",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarCollapseButtonColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton5",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton6",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton7",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarInControls",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarTextPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarTextFillSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarIconPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarIconSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton5",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton6",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton7",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarInControls",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtPreferAnimated",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtLyricsProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtRoundedCornerPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtCenterPlayIcon",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtNoCrop",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtectionCornerPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtHorizontalSwipeAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtTapAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtDoubleTapAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtLongPressAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtDropShadow",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtChangeAnimation",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverText1",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverText2",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverText3",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeNowPlayingQueuePosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeTopBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeBottomBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeCoverArtPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeControlsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeQueuePosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeQueueMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLyricsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLyricsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeCoverArtPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeControlsPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeQueuePercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLyricsPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeCoverFillSpace",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsHidden",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsRowPositioning",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.statusBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.statusBarProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.navigationBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.navigationBarProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderStyleMissingWaveForm",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderShowSpeed",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderShowTimes",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextInline",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderInvertColors",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarWidth",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarReflectionSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarReflectionIntensity",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderBarReflectionGap",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarIconColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarInvertColors",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarProtectionTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText1",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText2",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText3",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText4",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText5",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText6",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsVerticalFillSpace",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton1.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton5.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton6.overlayTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.sizeCustom",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.skipAmount",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.transparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton7.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ignoreStatusBar",
                "style.i.now.playing.page.configuration.landscapeConfiguration.invertTopAndBottomBars",
                "style.i.now.playing.page.configuration.landscapeConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.invertedOverlayColors",
                "style.i.now.playing.page.configuration.landscapeConfiguration.blackOverlayColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundCoverArtPreferAnimated",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundBlurLevel",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundProtectionBlack",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundGestureAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLeftOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeRightOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarTitle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarSubtitle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarIconSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarIconSpacingCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarShowCollapseButton",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarInvertButtonsAndCollapse",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarCollapseButtonColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton7",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarInControls",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarTextPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarTextFillSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarIconPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarIconSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton7",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarInControls",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtPreferAnimated",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtLyricsProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtRoundedCornerPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtCenterPlayIcon",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtNoCrop",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtectionCornerPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtHorizontalSwipeAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtTapAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtDoubleTapAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtLongPressAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtDropShadow",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtChangeAnimation",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverText1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverText2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverText3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeNowPlayingQueuePosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeTopBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeBottomBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeCoverArtPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeControlsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeQueuePosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeQueueMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLyricsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLyricsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeCoverArtPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeControlsPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeQueuePercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLyricsPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeCoverFillSpace",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsHidden",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsRowPositioning",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsBackgroundProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.statusBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.statusBarProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.navigationBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.navigationBarProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderStyleMissingWaveForm",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderShowSpeed",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderShowTimes",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextInline",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderInvertColors",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarWidth",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarReflectionSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarReflectionIntensity",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderBarReflectionGap",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarIconColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarInvertColors",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarProtectionTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsVerticalFillSpace",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton1.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.style",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton5.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton6.overlayTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.sizeCustom",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.skipAmount",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.transparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton7.overlayTransparency",
                "style.i.search.button.top.bar",
                "style.i.overview.row.q:favoriteplaylists",
                "style.i.overview.row.pl:a-44§§Dive Right In",
                "style.i.overview.row.q:favoriteartists",
                "style.i.overview.row.pl:s-45§§Right Now",
                "style.i.overview.row.s:shortcut",
                "style.i.overview.row.pl:s-47§§by Your Favorite Artists",
                "style.i.overview.row.pl:a-13§§Rediscover",
                "style.i.overview.row.q:lastplayedalbums",
                "style.i.overview.row.q:recentalbums",
                "style.i.overview.row.pl:s-58§§Hot Dial",
                "style.i.overview.row.q:favoriteradios",
                "style.i.overview.row.pl:s-49§§Forgotten Gems",
                "style.i.overview.row.q:mostplayedalbumartists",
                "style.i.overview.row.q:mostplayedsongs",
                "style.i.overview.row.pl:a-26§§Recently Added",
                "style.i.overview.row.q:recentalbumsartists",
                "style.i.overview.row.s:shortcut2",
                "style.i.overview.row.s:shortcut3",
                "style.i.overview.row.q:5starsongs",
                "style.i.overview.row.pl:s-16§§Daily Mix",
                "style.i.overview.row.pl:s-20§§Night Mix",
                "style.i.overview.row.s:shortcut4",
                "style.i.overview.row.q:rediscoversongs",
                "style.i.overview.row.pl:a-11§§Rediscover albums",
                "style.i.overview.row.pl:a-12§§Continue listening",
                "style.i.overview.row.q:rediscoveralbums",
                "style.i.overview.row.q:lastplayedsongs",
                "style.i.overview.row.q:favoritealbums",
                "style.i.overview.row.q:recentreleasealbum",
                "style.i.overview.row.q:resumealbums",
                "style.i.overview.row.q:randomalbumartists",
                "style.i.overview.row.q:randomartists",
                "style.i.overview.row.q:favoritesongs",
                "style.i.overview.row.q:recentartists",
                "style.i.overview.row.q:randomalbums",
                "style.i.overview.row.q:recentsongs",
                "style.i.overview.row.q:resumesongs",
                "style.i.overview.row.q:randomsongs",
                "style.i.overview.row.q:lastplayedartists",
                "style.i.overview.row.q:lastplayedalbumartists",
                "style.i.overview.row.q:mostplayedartists",
                "style.i.overview.row.q:mostplayedalbums",
                "style.i.overview.row.s:icon",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.i.home.rows.hidden",
                "style.a.custom.material.theme",
                "style.a.disable.color.animations",
                "style.a.theme.color.now.playing.style",
                "style.a.theme.color.np.surface",
                "style.a.theme.color.spec",
                "style.a.typography.custom",
                "style.d.album.page.configuration",
                "style.d.albums.list.display",
                "style.d.always.show.filter",
                "style.d.artist.page.configuration",
                "style.d.artists.list.display",
                "style.d.bottom.navigation.transparency",
                "style.d.floating.toolbar.full.width",
                "style.d.floating.toolbar.horizontal.padding",
                "style.d.collapsed.artist.albums",
                "style.d.collapsed.artist.songs",
                "style.d.collapsed.genre.albums",
                "style.d.collapsed.genre.artists",
                "style.d.collapsed.genre.songs",
                "style.d.field.list.0.display",
                "style.d.field.list.0.display.columns",
                "style.d.field.list.0.display.columns.landscape",
                "style.d.field.list.0.display.spacing",
                "style.d.field.list.0.display.spacing.landscape",
                "style.d.field.list.1.display",
                "style.d.field.list.1.display.columns",
                "style.d.field.list.1.display.columns.landscape",
                "style.d.field.list.1.display.spacing",
                "style.d.field.list.1.display.spacing.landscape",
                "style.d.field.list.2.display",
                "style.d.field.list.2.display.columns",
                "style.d.field.list.2.display.columns.landscape",
                "style.d.field.list.2.display.spacing",
                "style.d.field.list.2.display.spacing.landscape",
                "style.d.field.list.3.display",
                "style.d.field.list.3.display.columns",
                "style.d.field.list.3.display.columns.landscape",
                "style.d.field.list.3.display.spacing",
                "style.d.field.list.3.display.spacing.landscape",
                "style.d.field.list.4.display",
                "style.d.field.list.4.display.columns",
                "style.d.field.list.4.display.columns.landscape",
                "style.d.field.list.4.display.spacing",
                "style.d.field.list.4.display.spacing.landscape",
                "style.d.field.list.5.display",
                "style.d.field.list.5.display.columns",
                "style.d.field.list.5.display.columns.landscape",
                "style.d.field.list.5.display.spacing",
                "style.d.field.list.05.display.spacing.landscape",
                "style.d.field.list.6.display",
                "style.d.field.list.6.display.columns",
                "style.d.field.list6.display.columns.landscape",
                "style.d.field.list.6.display.spacing",
                "style.d.field.list.6.display.spacing.landscape",
                "style.d.field.list.7.display",
                "style.d.field.list.7.display.columns",
                "style.d.field.list.7.display.columns.landscape",
                "style.d.field.list.7.display.spacing",
                "style.d.field.list.7.display.spacing.landscape",
                "style.d.field.list.8.display",
                "style.d.field.list.8.display.columns",
                "style.d.field.list.8.display.columns.landscape",
                "style.d.field.list.8.display.spacing",
                "style.d.field.list.8.display.spacing.landscape",
                "style.d.field.list.9.display",
                "style.d.field.list.9.display.columns",
                "style.d.field.list.9.display.columns.landscape",
                "style.d.field.list.9.display.spacing",
                "style.d.field.list.9.display.spacing.landscape",
                "style.d.field.list.10.display",
                "style.d.field.list.10.display.columns",
                "style.d.field.list.10.display.columns.landscape",
                "style.d.field.list.10.display.spacing",
                "style.d.field.list.10.display.spacing.landscape",
                "style.d.field.list.11.display",
                "style.d.field.list.11.display.columns",
                "style.d.field.list.11.display.columns.landscape",
                "style.d.field.list.11.display.spacing",
                "style.d.field.list.11.display.spacing.landscape",
                "style.d.field.list.12.display",
                "style.d.field.list.12.display.columns",
                "style.d.field.list.12.display.columns.landscape",
                "style.d.field.list.12.display.spacing",
                "style.d.field.list.12.display.spacing.landscape",
                "style.d.field.list.13.display",
                "style.d.field.list.13.display.columns",
                "style.d.field.list.13.display.columns.landscape",
                "style.d.field.list.13.display.spacing",
                "style.d.field.list.13.display.spacing.landscape",
                "style.d.field.list.14.display",
                "style.d.field.list.14.display.columns",
                "style.d.field.list.14.display.columns.landscape",
                "style.d.field.list.14.display.spacing",
                "style.d.field.list.14.display.spacing.landscape",
                "style.d.genre.page.configuration",
                "style.d.genres.list.display",
                "style.d.header.album.thumbnail.style",
                "style.d.header.artist.thumbnail.style",
                "style.d.header.genre.thumbnail.style",
                "style.d.header.playlist.thumbnail.style",
                "style.d.hide.back.button.in.lists",
                "style.d.hide.favorite.button",
                "style.d.internet.radio.display",
                "style.d.internet.radio.display.columns",
                "style.d.internet.radio.display.columns.landscape",
                "style.d.internet.radio.display.spacing",
                "style.d.internet.radio.display.spacing.landscape",
                "style.d.inverted.sort.arrows",
                "style.d.list.hidden.button",
                "style.d.list.button.row.style",
                "style.d.list.grid.center",
                "style.d.list.text.only.single.line",
                "style.d.list.track.swipe.left.action",
                "style.d.list.track.swipe.right.action",
                "style.d.lyrics.center.text",
                "style.d.lyrics.drop.shadows",
                "style.d.lyrics.font.size.new",
                "style.d.lyrics.font.weight",
                "style.d.lyrics.hide.background.voice",
                "style.d.lyrics.hide.voice.color.indicator",
                "style.d.lyrics.hide.translations",
                "style.d.lyrics.inactive.alpha",
                "style.d.lyrics.inactive.scale",
                "style.d.lyrics.karaoke.enabled",
                "style.d.lyrics.letter.spacing",
                "style.d.lyrics.line.height",
                "style.d.lyrics.lyrics.spacing",
                "style.d.lyrics.show.close.button",
                "style.d.lyrics.show.control",
                "style.d.lyrics.show.progress",
                "style.d.lyrics.show.song.details",
                "style.d.lyrics.show.song.thumbnail.portrait",
                "style.d.lyrics.show.sync.buttons.bar",
                "style.d.lyrics.show.time.int",
                "style.d.lyrics.translations.cue",
                "style.d.lyrics.translations.colors",
                "style.d.navigation.inverted.position",
                "style.d.nav.type.landscape",
                "style.d.nav.type.portrait",
                "style.d.player.additional.info",
                "style.d.player.show.remaining",
                "style.d.player.show.track.number",
                "style.d.playlist.content.display",
                "style.d.playlist.content.display.columns",
                "style.d.playlist.content.display.columns.landscape",
                "style.d.playlist.content.display.spacing",
                "style.d.playlist.content.display.columns.spacing",
                "style.d.playlist.list.display",
                "style.d.playlist.page.configuration",
                "style.d.show.item.count",
                "style.d.show.favorite.overlay",
                "style.d.show.offline.overlay",
                "style.d.show.smart.filters.chips",
                "style.d.song.additional.info",
                "style.d.song.additional.info2",
                "style.d.songs.list.display",
                "style.ci",
            ),
            "now-playing.json" to listOf(
                "style.i.now.playing.page.configuration.compactConfiguration.size",
                "style.i.now.playing.page.configuration.compactConfiguration.floating",
                "style.i.now.playing.page.configuration.compactConfiguration.floatingProtection",
                "style.i.now.playing.page.configuration.compactConfiguration.floatingShadow",
                "style.i.now.playing.page.configuration.compactConfiguration.playerMargins",
                "style.i.now.playing.page.configuration.compactConfiguration.playerCornerRadius",
                "style.i.now.playing.page.configuration.compactConfiguration.leftHanded",
                "style.i.now.playing.page.configuration.compactConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.compactConfiguration.backgroundTransparency",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailRoundedCorners",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailClickPlayPause",
                "style.i.now.playing.page.configuration.compactConfiguration.thumbnailPauseButton",
                "style.i.now.playing.page.configuration.compactConfiguration.progressStyle",
                "style.i.now.playing.page.configuration.compactConfiguration.progressColor",
                "style.i.now.playing.page.configuration.compactConfiguration.progressNoBuffer",
                "style.i.now.playing.page.configuration.compactConfiguration.swipeToSkip",
                "style.i.now.playing.page.configuration.compactConfiguration.title.style",
                "style.i.now.playing.page.configuration.compactConfiguration.title.size",
                "style.i.now.playing.page.configuration.compactConfiguration.title.position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.color",
                "style.i.now.playing.page.configuration.compactConfiguration.title.protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.margins",
                "style.i.now.playing.page.configuration.compactConfiguration.title.ellipsis",
                "style.i.now.playing.page.configuration.compactConfiguration.title.customTextClick",
                "style.i.now.playing.page.configuration.compactConfiguration.title.customTextLongClick",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon1Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon2Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Position",
                "style.i.now.playing.page.configuration.compactConfiguration.title.icon3Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.style",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.size",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.color",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.margins",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.ellipsis",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.lineCount",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customTextClick",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.customTextLongClick",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon1Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon2Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Position",
                "style.i.now.playing.page.configuration.compactConfiguration.subtitle.icon3Protection",
                "style.i.now.playing.page.configuration.compactConfiguration.buttonsColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.ignoreStatusBar",
                "style.i.now.playing.page.configuration.portraitConfiguration.invertTopAndBottomBars",
                "style.i.now.playing.page.configuration.portraitConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.backgroundGestureAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLeftOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeRightOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarTitle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarSubtitle",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarIconSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarShowCollapseButton",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarCollapseButtonColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton5",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton6",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarButton7",
                "style.i.now.playing.page.configuration.portraitConfiguration.topBarInControls",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarIconPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarIconSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton5",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton6",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarButton7",
                "style.i.now.playing.page.configuration.portraitConfiguration.bottomBarInControls",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtRoundedCornerPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtCenterPlayIcon",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtNoCrop",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtectionCornerPercent",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtIconTransparency",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtHorizontalSwipeAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtTapAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtDoubleTapAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtLongPressAction",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton1",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton2",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton3",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtButton4",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtDropShadow",
                "style.i.now.playing.page.configuration.portraitConfiguration.coverArtChangeAnimation",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeNowPlayingQueuePosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeTopBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeBottomBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeCoverArtPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeControlsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeQueuePosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.landscapeLyricsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsOrder",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsRowPositioning",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlsBackgroundProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.statusBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.navigationBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderStyleMissingWaveForm",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderShowSpeed",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderShowTimes",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextInline",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderTextColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.sliderColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarIconColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.volumeBarColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarSize",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarColor",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarProtection",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.ratingBarMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText1",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText2",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText3",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText4",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText5",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlText6",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsStyle",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsMargins",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsPosition",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsSpacing",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButtonsVerticalFillSpace",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton2.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton3.size",
                "style.i.now.playing.page.configuration.portraitConfiguration.controlButton4.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ignoreStatusBar",
                "style.i.now.playing.page.configuration.landscapeConfiguration.invertTopAndBottomBars",
                "style.i.now.playing.page.configuration.landscapeConfiguration.adaptivePrimaryColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.backgroundGestureAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLeftOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeRightOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarTitle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarSubtitle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarIconSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarShowCollapseButton",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarCollapseButtonColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarButton7",
                "style.i.now.playing.page.configuration.landscapeConfiguration.topBarInControls",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarIconPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarIconSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarButton7",
                "style.i.now.playing.page.configuration.landscapeConfiguration.bottomBarInControls",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtRoundedCornerPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtCenterPlayIcon",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtRotation",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtNoCrop",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtectionCornerPercent",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtIconTransparency",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtHorizontalSwipeAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtTapAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtDoubleTapAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtLongPressAction",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtButton4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtDropShadow",
                "style.i.now.playing.page.configuration.landscapeConfiguration.coverArtChangeAnimation",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeNowPlayingQueuePosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeTopBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeBottomBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeCoverArtPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeControlsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeQueuePosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.landscapeLyricsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsOrder",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsRowPositioning",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlsBackgroundProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.statusBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.navigationBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderStyleMissingWaveForm",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderShowSpeed",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderShowTimes",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextInline",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderTextColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.sliderColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarIconColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.volumeBarColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarSize",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarColor",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarProtection",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.ratingBarMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText1",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText2",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText3",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText4",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText5",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlText6",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsStyle",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsMargins",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsPosition",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsSpacing",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButtonsVerticalFillSpace",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton2.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton3.size",
                "style.i.now.playing.page.configuration.landscapeConfiguration.controlButton4.size",
            ),
        )
    }
}
