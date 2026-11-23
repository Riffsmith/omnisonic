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

    companion object {

        @JvmStatic
        fun samples(): List<Arguments> = listOf(
            Arguments.of("application-a.json"),
            Arguments.of("application-b.json"),
            Arguments.of("now-playing.json"),
        )
    }
}
