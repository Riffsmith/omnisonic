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
import app.omnisonic.core.model.style.StyleCompactPlayer
import app.omnisonic.core.model.style.StyleControlButton
import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleExpandedPlayer
import app.omnisonic.core.model.style.StyleFontFamilies
import app.omnisonic.core.model.style.StyleHome
import app.omnisonic.core.model.style.StyleIssue
import app.omnisonic.core.model.style.StyleListLayout
import app.omnisonic.core.model.style.StyleNowPlaying
import app.omnisonic.core.model.style.StylePages
import app.omnisonic.core.model.style.StyleTextBlock
import app.omnisonic.core.model.style.StyleTextBlocks
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.Locale

sealed interface StyleConversionResult {

    data class Converted(
        val document: StyleDocument,
        val warnings: List<StyleIssue>,
    ) : StyleConversionResult

    data class Rejected(val errors: List<StyleIssue>) : StyleConversionResult {
        val firstMessage: String get() = errors.first().message
    }
}

/**
 * One-way import-time conversion of a third-party style export into schema v1
 * (`docs/omnisonic/style/style-converter.md`, D-63/D-64).
 *
 * Only values whose meaning is evidenced are carried over. Everything else is
 * reported as a warning and dropped; a malformed container or a document with
 * no style sections is rejected. The finished document is written and decoded
 * again before it is returned, so a successful conversion is always valid v1.
 */
object StyleConverter {

    private const val ROOT = "\$"
    private const val STYLE = "style"

    private const val KEY_APP = "a"
    private const val KEY_INTERFACE = "i"
    private const val KEY_DISPLAY = "d"
    private const val KEY_IMAGE = "ci"

    private val treeAdapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    private val usableId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun convert(text: String): StyleConversionResult {
        val issues = ConversionIssues()
        val root = parseObject(text, ROOT, issues) ?: return rejected(issues)
        val type = containerText(root, "type", issues) ?: return rejected(issues)
        if (type != "application" && type != "nowplaying") {
            issues.error("type", "unsupported style type '$type'")
            return rejected(issues)
        }
        val id = containerText(root, "id", issues) ?: return rejected(issues)
        if (!usableId.matches(id)) {
            issues.error("id", "not a usable style id")
            return rejected(issues)
        }
        val name = containerText(root, "name", issues) ?: return rejected(issues)
        val styleText = containerText(root, "style", issues) ?: return rejected(issues)
        val style = parseObject(styleText, STYLE, issues) ?: return rejected(issues)
        val sections = listOf(KEY_INTERFACE, KEY_APP, KEY_DISPLAY).filter { style.containsKey(it) }
        if (sections.isEmpty()) {
            issues.error(STYLE, "no style sections found (expected $KEY_INTERFACE, $KEY_APP or $KEY_DISPLAY)")
            return rejected(issues)
        }
        sections.forEach { key ->
            if (style[key] !is Map<*, *>) {
                issues.error(child(STYLE, key), "expected object, got ${typeName(style[key])}")
            }
        }
        if (issues.hasErrors) return rejected(issues)

        val ui = convertInterface(style[KEY_INTERFACE] as? Map<String, Any?>, issues)
        val app = convertApp(style[KEY_APP] as? Map<String, Any?>, issues)
        val pages = convertPages(style[KEY_DISPLAY] as? Map<String, Any?>, issues)
        style.forEach { (key, _) ->
            when (key) {
                KEY_INTERFACE, KEY_APP, KEY_DISPLAY -> Unit
                KEY_IMAGE -> issues.warn(child(STYLE, key), "embedded image is not carried over")
                else -> issues.warn(child(STYLE, key), "unknown section is not carried over")
            }
        }

        val document = StyleDocument(
            id = id,
            name = name,
            app = app,
            home = ui.home,
            pages = pages,
            nowPlaying = ui.nowPlaying,
        )
        val written = when (val result = StyleWriter.write(document)) {
            is StyleWriteResult.Written -> result.text
            is StyleWriteResult.Failed -> return StyleConversionResult.Rejected(result.errors)
        }
        return when (val decoded = StyleDecoder.decode(written)) {
            is StyleDecodeResult.Decoded -> StyleConversionResult.Converted(decoded.document, issues.warnings)
            is StyleDecodeResult.Rejected -> StyleConversionResult.Rejected(decoded.errors)
        }
    }

    private fun rejected(issues: ConversionIssues): StyleConversionResult.Rejected =
        StyleConversionResult.Rejected(issues.errors)

    private fun containerText(
        root: Map<String, Any?>,
        key: String,
        issues: ConversionIssues,
    ): String? {
        val value = root[key]
        if (value == null) {
            issues.error(key, "required field is missing")
            return null
        }
        val text = value as? String
        if (text == null) {
            issues.error(key, "expected string, got ${typeName(value)}")
            return null
        }
        if (text.isBlank()) {
            issues.error(key, "must not be blank")
            return null
        }
        return text
    }

    private fun parseObject(
        text: String,
        path: String,
        issues: ConversionIssues,
    ): Map<String, Any?>? {
        val tree = try {
            treeAdapter.fromJson(text)
        } catch (failure: IOException) {
            issues.error(path, "malformed JSON: ${failure.message ?: "unreadable document"}")
            return null
        } catch (failure: JsonDataException) {
            issues.error(path, "malformed JSON: ${failure.message ?: "unreadable document"}")
            return null
        }
        if (tree !is Map<*, *>) {
            issues.error(path, "expected object, got ${typeName(tree)}")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return tree as Map<String, Any?>
    }

    private fun parseNestedObject(
        value: Any?,
        path: String,
        issues: ConversionIssues,
    ): Map<String, Any?>? {
        if (value !is String) {
            issues.warn(path, "expected a JSON string; not carried over")
            return null
        }
        val tree = try {
            treeAdapter.fromJson(value)
        } catch (failure: IOException) {
            issues.warn(path, "malformed nested JSON: ${failure.message ?: "unreadable value"}; not carried over")
            return null
        } catch (failure: JsonDataException) {
            issues.warn(path, "malformed nested JSON: ${failure.message ?: "unreadable value"}; not carried over")
            return null
        }
        if (tree !is Map<*, *>) {
            issues.warn(path, "expected a JSON object, got ${typeName(tree)}; not carried over")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return tree as Map<String, Any?>
    }

    private fun convertApp(node: Map<String, Any?>?, issues: ConversionIssues): StyleApp {
        if (node == null) return StyleApp()
        var app = StyleApp()
        node.forEach { (key, value) ->
            val path = child("$STYLE.$KEY_APP", key)
            when (key) {
                "theme.color.seed" -> {
                    val seed = seedColor(value, path, issues)
                    if (seed != null) app = app.copy(seedColor = seed)
                }

                "theme.color.source" -> {
                    val source = colorSource(value, path, issues)
                    if (source != null) app = app.copy(colorSource = source)
                }

                "dark.mode" -> {
                    val mode = darkMode(value, path, issues)
                    if (mode != null) app = app.copy(darkMode = mode)
                }

                "theme.color.palette" -> {
                    val palette = palette(value, path, issues)
                    if (palette != null) app = app.copy(colorPalette = palette)
                }

                "theme.color.contrast" -> {
                    val contrast = contrast(value, path, issues)
                    if (contrast != null) app = app.copy(colorContrast = contrast)
                }

                "theme.corner.size" -> {
                    val corner = cornerScale(value, path, issues)
                    if (corner != null) app = app.copy(cornerScale = corner)
                }

                "typography" -> {
                    val family = value as? String
                    when {
                        family.isNullOrBlank() ->
                            issues.warn(path, "font family is missing or blank; not carried over")

                        !isPortableFontFamily(family) ->
                            issues.warn(path, "font family is a path or URI; not carried over")

                        else -> app = app.copy(
                            fontFamilies = StyleFontFamilies(
                                title = family,
                                body = family,
                                lyrics = family,
                                nav = family,
                            ),
                        )
                    }
                }

                "custom.material.theme" -> issues.warn(path, "custom colour scheme is not carried over")
                "theme.color.spec" -> issues.warn(path, "colour specification is not carried over")
                "theme.color.now.playing.style" -> issues.warn(path, "now-playing colour style is not carried over")
                "theme.color.np.surface" -> issues.warn(path, "now-playing surface colour is not carried over")
                "disable.color.animations" -> issues.warn(path, "colour-animation switch is not carried over")
                "typography.custom" -> issues.warn(path, "custom font choice is not carried over")
                else -> issues.warn(path, "not carried over")
            }
        }
        return app
    }

    /**
     * Names every part of [text] the v1 renderer does not understand. v1 templates
     * carry only the evidenced placeholders; conditional and escape syntax is out
     * of scope until TODO 8b defines a template grammar.
     */
    private fun unsupportedTemplateSyntax(text: String): Set<String> {
        val unsupported = linkedSetOf<String>()
        if (text.any { it == '{' || it == '}' || it == '\\' }) {
            unsupported += "conditional or escape sequence"
        }
        val segments = text.split('%')
        if (segments.size % 2 == 0) {
            unsupported += "unterminated placeholder"
            return unsupported
        }
        segments.forEachIndexed { index, segment ->
            if (index % 2 == 1) {
                val token = "%$segment%"
                if (token !in SUPPORTED_PLACEHOLDERS) unsupported += token
            }
        }
        return unsupported
    }

    private fun isPortableFontFamily(family: String): Boolean =
        !family.startsWith("/") &&
            !WINDOWS_ABSOLUTE.containsMatchIn(family) &&
            !UNC_PREFIX.containsMatchIn(family) &&
            !URI_SCHEME.containsMatchIn(family)

    private fun seedColor(value: Any?, path: String, issues: ConversionIssues): String? {
        val number = when (value) {
            is Number -> {
                val double = value.toDouble()
                if (double % 1.0 != 0.0) null else double.toLong()
            }

            is String -> value.toLongOrNull()
            else -> null
        }
        if (number == null || number < Int.MIN_VALUE || number > 0xFFFFFFFFL) {
            issues.warn(path, "seed colour is not an ARGB integer; not carried over")
            return null
        }
        return String.format(Locale.ROOT, "#%08X", number and 0xFFFFFFFFL)
    }

    private fun colorSource(value: Any?, path: String, issues: ConversionIssues): ColorSource? {
        val text = value as? String
        return when (text) {
            "system" -> ColorSource.SYSTEM
            "custom" -> ColorSource.SEED
            else -> {
                issues.warn(path, "colour source '${text ?: typeName(value)}' is not evidenced; not carried over")
                null
            }
        }
    }

    private fun darkMode(value: Any?, path: String, issues: ConversionIssues): DarkMode? {
        val text = value as? String
        return when (text) {
            "auto" -> DarkMode.SYSTEM
            else -> {
                issues.warn(path, "dark mode '${text ?: typeName(value)}' is not evidenced; not carried over")
                null
            }
        }
    }

    private fun palette(value: Any?, path: String, issues: ConversionIssues): ColorPalette? {
        val text = value as? String
        val mapped = when (text) {
            "TonalSpot" -> ColorPalette.TONAL_SPOT
            "Neutral" -> ColorPalette.NEUTRAL
            "Vibrant" -> ColorPalette.VIBRANT
            "Expressive" -> ColorPalette.EXPRESSIVE
            "Rainbow" -> ColorPalette.RAINBOW
            "FruitSalad" -> ColorPalette.FRUIT_SALAD
            "Monochrome" -> ColorPalette.MONOCHROME
            "Fidelity" -> ColorPalette.FIDELITY
            "Content" -> ColorPalette.CONTENT
            else -> null
        }
        if (mapped == null) {
            issues.warn(path, "palette '${text ?: typeName(value)}' is not evidenced; not carried over")
        }
        return mapped
    }

    private fun contrast(value: Any?, path: String, issues: ConversionIssues): ColorContrast? {
        val text = value as? String
        val mapped = when (text) {
            "normal" -> ColorContrast.STANDARD
            "medium" -> ColorContrast.MEDIUM
            "high" -> ColorContrast.HIGH
            else -> null
        }
        if (mapped == null) {
            issues.warn(path, "contrast '${text ?: typeName(value)}' is not evidenced; not carried over")
        }
        return mapped
    }

    private fun cornerScale(value: Any?, path: String, issues: ConversionIssues): CornerRoundness? {
        val ordinal = intValue(value, path, issues) ?: return null
        return when (ordinal) {
            0 -> CornerRoundness.DEFAULT
            1 -> CornerRoundness.MEDIUM
            2 -> CornerRoundness.LARGE
            3 -> CornerRoundness.EXTRA_LARGE
            else -> {
                issues.warn(path, "corner size ordinal $ordinal has no evidenced meaning; not carried over")
                null
            }
        }
    }

    private data class InterfaceConversion(
        val home: StyleHome,
        val nowPlaying: StyleNowPlaying,
    )

    private fun convertInterface(node: Map<String, Any?>?, issues: ConversionIssues): InterfaceConversion {
        if (node == null) return InterfaceConversion(StyleHome(), StyleNowPlaying())
        var visibleRows: List<String>? = null
        var hiddenRows: List<String>? = null
        var nowPlaying = StyleNowPlaying()
        node.forEach { (key, value) ->
            val path = child("$STYLE.$KEY_INTERFACE", key)
            when (key) {
                "home.rows" -> visibleRows = tokenList(value, path, issues)
                "home.rows.hidden" -> hiddenRows = tokenList(value, path, issues)
                "now.playing.page.configuration" -> nowPlaying = convertNowPlaying(value, path, issues)
                "home.custom.icon" -> issues.warn(path, absolutePathDetail(value))
                else -> issues.warn(path, interfaceDetail(key))
            }
        }
        return InterfaceConversion(buildHome(visibleRows, hiddenRows, issues), nowPlaying)
    }

    private fun tokenList(value: Any?, path: String, issues: ConversionIssues): List<String>? {
        val text = value as? String
        if (text == null) {
            issues.warn(path, "row list is not a string; not carried over")
            return null
        }
        return text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun buildHome(
        visibleRows: List<String>?,
        hiddenRows: List<String>?,
        issues: ConversionIssues,
    ): StyleHome {
        if (visibleRows == null && hiddenRows == null) return StyleHome()
        val entries = mutableListOf<HomeSectionEntry>()
        val seen = mutableSetOf<HomeSection>()
        addRows(visibleRows, "style.i.home.rows", visible = true, entries, seen, issues)
        addRows(hiddenRows, "style.i.home.rows.hidden", visible = false, entries, seen, issues)
        return StyleHome(sections = entries)
    }

    private fun addRows(
        tokens: List<String>?,
        path: String,
        visible: Boolean,
        entries: MutableList<HomeSectionEntry>,
        seen: MutableSet<HomeSection>,
        issues: ConversionIssues,
    ) {
        tokens?.forEachIndexed { index, token ->
            val section = homeSection(token, index, path, issues) ?: return@forEachIndexed
            if (!seen.add(section)) {
                issues.warn(path, "row token '$token' at position ${index + 1} repeats an earlier row; not carried over")
                return@forEachIndexed
            }
            entries += HomeSectionEntry(section, visible = visible)
        }
    }

    private fun homeSection(token: String, position: Int, path: String, issues: ConversionIssues): HomeSection? {
        if (!token.startsWith("q:")) {
            issues.warn(path, "row token '$token' at position ${position + 1} is not carried over")
            return null
        }
        val name = token.removePrefix("q:")
        return when {
            name.startsWith("lastplayed") -> HomeSection.RECENTLY_PLAYED
            name.startsWith("recent") -> HomeSection.RECENTLY_ADDED
            name.startsWith("mostplayed") -> HomeSection.MOST_PLAYED
            name.startsWith("favorite") -> HomeSection.FAVORITES
            else -> {
                issues.warn(path, "row token '$token' at position ${position + 1} is not carried over")
                null
            }
        }
    }

    private fun interfaceDetail(key: String): String = when {
        key.startsWith("overview.row.") -> "per-row configuration is not carried over"
        key.startsWith("home.shortcuts") -> "shortcut slot is not carried over"
        key.startsWith("home.icon") || key == "app.icon" || key == "tint.home.icon" ->
            "home icon configuration is not carried over"

        key == "main.tabs" -> "tab composition is not carried over"
        key == "library.shortcuts" -> "library shortcut order is not carried over"
        key == "library.page.configuration" -> "library page configuration is not carried over"
        key.startsWith("widget.hide.") -> "widget button visibility is not carried over"
        else -> "not carried over"
    }

    private fun absolutePathDetail(value: Any?): String =
        if (value is String && value.startsWith("/")) {
            "absolute path is not portable; not carried over"
        } else {
            "custom icon is not carried over"
        }

    private fun convertPages(node: Map<String, Any?>?, issues: ConversionIssues): StylePages {
        if (node == null) return StylePages()
        val layouts = mutableMapOf<String, LayoutBuilder>()
        node.forEach { (key, value) ->
            val path = child("$STYLE.$KEY_DISPLAY", key)
            val list = listTypeFor(key)
            if (list != null) {
                val builder = layouts.getOrPut(list) { LayoutBuilder() }
                when {
                    key.endsWith(".columns.landscape") ->
                        builder.columnsLandscape = columns(value, path, issues)

                    key.endsWith(".columns") ->
                        builder.columnsPortrait = columns(value, path, issues)

                    key.endsWith(".spacing.landscape") ->
                        builder.spacingLandscape = spacing(value, path, issues)

                    key.endsWith(".spacing") ->
                        builder.spacingPortrait = spacing(value, path, issues)

                    else -> issues.warn(path, pagesDetail(key))
                }
            } else {
                issues.warn(path, pagesDetail(key))
            }
        }
        return StylePages(
            albums = layouts["albums"]?.build() ?: StyleListLayout(),
            artists = layouts["artists"]?.build() ?: StyleListLayout(),
            genres = layouts["genres"]?.build() ?: StyleListLayout(),
            songs = layouts["songs"]?.build() ?: StyleListLayout(),
            playlists = layouts["playlists"]?.build() ?: StyleListLayout(),
        )
    }

    private fun listTypeFor(key: String): String? = when {
        key.startsWith("albums.list.display.") -> "albums"
        key.startsWith("artists.list.display.") -> "artists"
        key.startsWith("genres.list.display.") -> "genres"
        key.startsWith("songs.list.display.") -> "songs"
        key.startsWith("playlist.list.display.") -> "playlists"
        else -> null
    }

    private fun columns(value: Any?, path: String, issues: ConversionIssues): Int? {
        val ordinal = intValue(value, path, issues) ?: return null
        if (ordinal !in 1..12) {
            issues.warn(path, "column count $ordinal is out of range; kept the default")
            return null
        }
        return ordinal
    }

    private fun spacing(value: Any?, path: String, issues: ConversionIssues): Float? {
        val number = floatValue(value, path, issues) ?: return null
        if (number !in 0f..64f) {
            issues.warn(path, "spacing $number is out of range; kept the default")
            return null
        }
        return number
    }

    private class LayoutBuilder {
        var columnsPortrait: Int? = null
        var columnsLandscape: Int? = null
        var spacingPortrait: Float? = null
        var spacingLandscape: Float? = null

        fun build(): StyleListLayout = StyleListLayout(
            columns = OrientationPair(
                portrait = columnsPortrait ?: StyleListLayout.DEFAULT_COLUMNS.portrait,
                landscape = columnsLandscape ?: StyleListLayout.DEFAULT_COLUMNS.landscape,
            ),
            spacingDp = OrientationPair(
                portrait = spacingPortrait ?: StyleListLayout.DEFAULT_SPACING.portrait,
                landscape = spacingLandscape ?: StyleListLayout.DEFAULT_SPACING.landscape,
            ),
        )
    }

    private fun pagesDetail(key: String): String = when {
        key.endsWith(".display") -> "display mode ordinal has no evidenced meaning; not carried over"
        key.startsWith("nav.type.") -> "navigation type ordinal has no evidenced meaning; not carried over"
        key.contains("page.configuration") -> "page configuration is not carried over"
        key.startsWith("lyrics.") -> "lyric styling is not carried over"
        key.startsWith("field.list.") -> "additional list layout is not carried over"
        key.startsWith("header.") -> "header thumbnail style is not carried over"
        else -> "not carried over"
    }

    private fun convertNowPlaying(value: Any?, path: String, issues: ConversionIssues): StyleNowPlaying {
        val config = parseNestedObject(value, path, issues) ?: return StyleNowPlaying()
        var compact = StyleCompactPlayer()
        var portrait = StyleExpandedPlayer()
        var landscape = StyleExpandedPlayer()
        config.forEach { (key, node) ->
            val childPath = child(path, key)
            when (key) {
                "compactConfiguration" -> compact = convertCompact(node, childPath, issues)
                "portraitConfiguration" -> portrait = convertExpanded(node, childPath, issues)
                "landscapeConfiguration" -> landscape = convertExpanded(node, childPath, issues)
                else -> issues.warn(childPath, "not carried over")
            }
        }
        return StyleNowPlaying(
            compact = compact,
            expanded = OrientationPair(portrait = portrait, landscape = landscape),
        )
    }

    private fun convertCompact(value: Any?, path: String, issues: ConversionIssues): StyleCompactPlayer {
        val config = asObject(value, path, issues) ?: return StyleCompactPlayer()
        var text = StyleTextBlocks()
        var buttons = StyleCompactPlayer.DEFAULT_BUTTONS
        config.forEach { (key, node) ->
            val childPath = child(path, key)
            when (key) {
                "title" -> text = text.copy(
                    title = convertTextBlock(node, childPath, issues, fallbackTemplate = "%title%"),
                )

                "subtitle" -> text = text.copy(
                    subtitle = convertTextBlock(node, childPath, issues, fallbackTemplate = "%artist%"),
                )

                "buttons" -> buttons = convertCompactButtons(node, childPath, issues)
                else -> issues.warn(childPath, compactDetail(key))
            }
        }
        return StyleCompactPlayer(text = text, buttons = buttons)
    }

    private fun convertTextBlock(
        value: Any?,
        path: String,
        issues: ConversionIssues,
        fallbackTemplate: String,
    ): StyleTextBlock {
        val node = asObject(value, path, issues) ?: return StyleTextBlock(template = fallbackTemplate)
        var template = fallbackTemplate
        var templateSet = false
        var maxLines = 1
        node.forEach { (key, value) ->
            val childPath = child(path, key)
            when (key) {
                "customText" -> {
                    val text = value as? String
                    when {
                        text == null -> issues.warn(childPath, "text template is not a string; not carried over")
                        text.isBlank() -> Unit
                        else -> {
                            val unsupported = unsupportedTemplateSyntax(text)
                            if (unsupported.isEmpty()) {
                                template = text
                                templateSet = true
                            } else {
                                issues.warn(
                                    childPath,
                                    "template syntax ${unsupported.joinToString()} is not supported; kept '$fallbackTemplate'",
                                )
                            }
                        }
                    }
                }

                "text" -> {
                    val ordinal = intValue(value, childPath, issues)
                    if (ordinal != null) {
                        val mapped = when (ordinal) {
                            0 -> ""
                            1 -> "%title%"
                            2 -> "%album%"
                            3 -> "%artist%"
                            else -> null
                        }
                        when {
                            mapped == null -> issues.warn(
                                childPath,
                                "text enum ordinal $ordinal has no evidenced meaning; not carried over",
                            )

                            !templateSet -> template = mapped
                        }
                    }
                }

                "lineCount" -> {
                    val lines = intValue(value, childPath, issues)
                    if (lines != null) {
                        if (lines in 1..4) {
                            maxLines = lines
                        } else {
                            issues.warn(childPath, "line count $lines is not representable; kept $maxLines")
                        }
                    }
                }

                else -> issues.warn(childPath, "not carried over")
            }
        }
        return StyleTextBlock(template = template, maxLines = maxLines)
    }

    private fun convertCompactButtons(
        value: Any?,
        path: String,
        issues: ConversionIssues,
    ): List<StyleControlButton> {
        if (value !is List<*>) {
            issues.warn(path, "button list is not an array; kept the default")
            return StyleCompactPlayer.DEFAULT_BUTTONS
        }
        return value.mapIndexedNotNull { index, item ->
            val itemPath = "$path[$index]"
            val name = item as? String
            if (name == null) {
                issues.warn(itemPath, "button name is not a string; not carried over")
                return@mapIndexedNotNull null
            }
            when (name) {
                "play" -> StyleControlButton(PlayerAction.PLAY_PAUSE)
                "favorite" -> StyleControlButton(PlayerAction.FAVORITE)
                else -> {
                    issues.warn(itemPath, "button action '$name' is not evidenced; not carried over")
                    null
                }
            }
        }
    }

    private fun convertExpanded(value: Any?, path: String, issues: ConversionIssues): StyleExpandedPlayer {
        val config = asObject(value, path, issues) ?: return StyleExpandedPlayer()
        config.forEach { (key, _) ->
            if (!key.matches(CONTROL_BUTTON)) {
                issues.warn(child(path, key), expandedDetail(key))
            }
        }
        val slots = config.entries
            .mapNotNull { (key, node) ->
                CONTROL_BUTTON.matchEntire(key)?.let { match -> match.groupValues[1].toInt() to node }
            }
            .sortedBy { (slot, _) -> slot }
        val buttons = slots.mapNotNull { (slot, node) ->
            convertControlButton(node, child(path, "controlButton$slot"), issues)
        }
        return StyleExpandedPlayer(
            buttons = if (slots.isEmpty()) StyleExpandedPlayer.DEFAULT_BUTTONS else buttons,
        )
    }

    private fun convertControlButton(
        value: Any?,
        path: String,
        issues: ConversionIssues,
    ): StyleControlButton? {
        val node = asObject(value, path, issues) ?: return null
        var action: PlayerAction? = null
        var hasAction = false
        node.forEach { (key, value) ->
            val childPath = child(path, key)
            when (key) {
                "action" -> {
                    hasAction = true
                    val ordinal = intValue(value, childPath, issues) ?: return@forEach
                    action = actionFor(ordinal, childPath, issues)
                }

                "style" -> {
                    val ordinal = intValue(value, childPath, issues) ?: return@forEach
                    if (ordinal != 0) {
                        issues.warn(childPath, "button style ordinal $ordinal is not representable; kept the default")
                    }
                }

                "size" -> {
                    val ordinal = intValue(value, childPath, issues) ?: return@forEach
                    if (ordinal != 0) {
                        issues.warn(childPath, "button size ordinal $ordinal has no evidenced meaning; kept the default")
                    }
                }

                else -> issues.warn(childPath, "not carried over")
            }
        }
        if (!hasAction) issues.warn(path, "button action is missing; not carried over")
        return action?.let { StyleControlButton(action = it) }
    }

    private fun actionFor(ordinal: Int, path: String, issues: ConversionIssues): PlayerAction? = when (ordinal) {
        0 -> null
        1 -> PlayerAction.PLAY_PAUSE
        2 -> PlayerAction.STOP
        3 -> PlayerAction.PREVIOUS
        4 -> PlayerAction.NEXT
        5 -> PlayerAction.SEEK_BACK_10
        6 -> PlayerAction.SEEK_FORWARD_10
        9, 31 -> PlayerAction.REPEAT
        10, 30 -> PlayerAction.SHUFFLE
        7, 8, 40, 41, 42, 43, 100 -> {
            issues.warn(path, "action ordinal $ordinal is not representable; not carried over")
            null
        }

        else -> {
            issues.warn(path, "action ordinal $ordinal has no evidenced meaning; not carried over")
            null
        }
    }

    private fun compactDetail(key: String): String = when {
        key == "progressStyle" -> "progress style ordinal has no evidenced meaning; not carried over"
        key.startsWith("background") -> "background preset ordinal is not carried over"
        key == "playerCornerRadius" || key == "playerMargins" -> "player geometry is not carried over"
        key.startsWith("thumbnail") -> "thumbnail configuration is not carried over"
        key == "buttonsColor" || key == "progressColor" -> "colour choice is not carried over"
        else -> "not carried over"
    }

    private fun expandedDetail(key: String): String = when {
        key.startsWith("controlText") -> "additional text block is not carried over"
        key == "controlsOrder" -> "control order is not carried over"
        key.startsWith("background") -> "background preset ordinal is not carried over"
        key.startsWith("slider") -> "slider styling is not carried over"
        key.startsWith("volumeBar") -> "volume bar styling is not carried over"
        key.startsWith("ratingBar") -> "rating bar styling is not carried over"
        key.startsWith("topBar") || key.startsWith("bottomBar") || key.startsWith("coverArt") ->
            "bar or artwork configuration is not carried over"

        else -> "not carried over"
    }

    private fun asObject(value: Any?, path: String, issues: ConversionIssues): Map<String, Any?>? {
        if (value !is Map<*, *>) {
            issues.warn(path, "expected object, got ${typeName(value)}; not carried over")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun intValue(value: Any?, path: String, issues: ConversionIssues): Int? {
        if (value !is Number) {
            issues.warn(path, "expected number, got ${typeName(value)}; not carried over")
            return null
        }
        val number = value.toDouble()
        if (number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
            issues.warn(path, "expected an integer, got $value; not carried over")
            return null
        }
        return number.toInt()
    }

    private fun floatValue(value: Any?, path: String, issues: ConversionIssues): Float? {
        if (value !is Number) {
            issues.warn(path, "expected number, got ${typeName(value)}; not carried over")
            return null
        }
        return value.toFloat()
    }

    private fun child(parent: String, key: String): String =
        if (parent == ROOT) key else "$parent.$key"

    private fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> "object"
        is List<*> -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        else -> "value"
    }

    private val CONTROL_BUTTON = Regex("controlButton([1-7])")
    private val SUPPORTED_PLACEHOLDERS = setOf("%title%", "%artist%", "%album%")
    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/]")
    private val UNC_PREFIX = Regex("^\\\\\\\\")
    private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    private class ConversionIssues {
        private val errorList = mutableListOf<StyleIssue>()
        private val warningList = linkedSetOf<StyleIssue>()

        val errors: List<StyleIssue> get() = errorList
        val warnings: List<StyleIssue> get() = warningList.toList()
        val hasErrors: Boolean get() = errorList.isNotEmpty()

        fun error(path: String, detail: String) {
            errorList += StyleIssue(path, detail)
        }

        fun warn(path: String, detail: String) {
            warningList += StyleIssue(path, detail)
        }
    }
}
