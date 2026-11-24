package app.omnisonic.style

import app.omnisonic.core.model.style.BackdropLayerKind
import app.omnisonic.core.model.style.ControlButtonSize
import app.omnisonic.core.model.style.ControlButtonStyle
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.NowPlayingBlockKind
import app.omnisonic.core.model.style.PlayerAction
import app.omnisonic.core.model.style.TextAlignment
import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleNormalizer
import app.omnisonic.core.model.style.StyleWriteResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Validates the shipped built-in profile assets (`src/main/assets/styles`) through
 * the same decoder/normalizer path as imports (`docs/omnisonic/style/style-profiles.md`
 * §Built-ins, D-77).
 */
class BuiltinStyleAssetsTest {

    private val assetDir = File("src/main/assets/styles")

    private fun assetFiles(): List<File> =
        assetDir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun assetText(file: File): String = file.readText()

    private fun decodeAsset(file: File): StyleDecodeResult.Decoded {
        val result = StyleDecoder.decode(assetText(file))
        assertThat(result).isInstanceOf(StyleDecodeResult.Decoded::class.java)
        return result as StyleDecodeResult.Decoded
    }

    private fun brokenFixtureText(): String {
        val url = requireNotNull(javaClass.classLoader.getResource("style-samples/broken-builtin.json")) {
            "broken-builtin.json missing from test resources"
        }
        return File(url.toURI()).readText()
    }

    @Test
    fun exactlySevenAssetsAreShipped() {
        val files = assetFiles()
        assertThat(files).hasSize(7)
        assertThat(files.map { it.name }).containsExactly(
            "builtin-default.json",
            "builtin-compact-list.json",
            "builtin-large-cover.json",
            "builtin-tablet-wide.json",
            "builtin-player-artwork.json",
            "builtin-player-minimal.json",
            "builtin-player-queue.json",
        )
    }

    @Test
    fun everyAssetDecodesAsValidV1WithZeroWarnings() {
        for (file in assetFiles()) {
            val decoded = decodeAsset(file)
            assertThat(decoded.warnings).isEmpty()
            assertThat(decoded.document.schemaVersion).isEqualTo(1)
        }
    }

    @Test
    fun idsAreDistinctStableAndMatchFileNames() {
        val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val ids = assetFiles().map { file ->
            val document = decodeAsset(file).document
            assertThat(document.id).matches(idPattern.pattern)
            assertThat(file.name).isEqualTo(document.id + ".json")
            document.id
        }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun namesAreTheExpectedHumanReadableCoinages() {
        val expected = mapOf(
            "builtin-default" to "Default Style",
            "builtin-compact-list" to "Compact List",
            "builtin-large-cover" to "Large Cover",
            "builtin-tablet-wide" to "Tablet Wide",
            "builtin-player-artwork" to "Artwork Player",
            "builtin-player-minimal" to "Minimal Player",
            "builtin-player-queue" to "Queue Player",
        )
        for (file in assetFiles()) {
            val document = decodeAsset(file).document
            assertThat(document.name).isEqualTo(expected.getValue(document.id))
        }
    }

    @Test
    fun theDefaultBuiltinResolvesExactlyToTheBaseStyleTokens() {
        val document = decodeAsset(File(assetDir, "builtin-default.json")).document

        assertThat(StyleTokenResolver.resolve(document)).isEqualTo(DefaultStyleTokens)
        assertThat(document.pages.albums.columns).isEqualTo(
            app.omnisonic.core.model.style.OrientationPair(portrait = 3, landscape = 5),
        )
        assertThat(document.pages.albums.spacingDp).isEqualTo(
            app.omnisonic.core.model.style.OrientationPair(portrait = 8f, landscape = 8f),
        )
    }

    @Test
    fun everyBuiltinNowPlayingDefaultsMatchTheShippedScreens() {
        for (file in assetFiles()) {
            val nowPlaying = decodeAsset(file).document.nowPlaying
            val compact = nowPlaying.compact
            val compactPlayPause = compact.buttons.single { it.action == PlayerAction.PLAY_PAUSE }
            assertThat(compactPlayPause.style).isEqualTo(ControlButtonStyle.DEFAULT)
            assertThat(compactPlayPause.size).isEqualTo(ControlButtonSize.MEDIUM)
            assertThat(compact.progress.showRemainingTime).isFalse()

            for (expanded in listOf(nowPlaying.expanded.portrait, nowPlaying.expanded.landscape)) {
                assertThat(expanded.text.title.alignment).isEqualTo(TextAlignment.CENTER)
                assertThat(expanded.text.title.maxLines).isEqualTo(2)
                assertThat(expanded.text.subtitle.alignment).isEqualTo(TextAlignment.CENTER)
                assertThat(expanded.text.subtitle.maxLines).isEqualTo(1)
                assertThat(expanded.progress.showRemainingTime).isTrue()
            }
        }
    }

    @Test
    fun brokenFixtureIsRejectedByTheSameValidator() {
        val result = StyleDecoder.decode(brokenFixtureText())
        assertThat(result).isInstanceOf(StyleDecodeResult.Rejected::class.java)
        val rejected = result as StyleDecodeResult.Rejected
        assertThat(rejected.firstMessage)
            .isEqualTo("pages.albums.columns.portrait: value 0 is out of range 1..12")

        val normalized = StyleNormalizer.normalize(brokenFixtureText())
        assertThat(normalized).isInstanceOf(StyleWriteResult.Failed::class.java)
        assertThat((normalized as StyleWriteResult.Failed).firstMessage)
            .isEqualTo("pages.albums.columns.portrait: value 0 is out of range 1..12")
    }

    @Test
    fun exportOfEveryAssetIsByteIdenticalAfterNormalization() {
        for (file in assetFiles()) {
            val text = assetText(file)
            val result = StyleNormalizer.normalize(text)
            assertThat(result).isInstanceOf(StyleWriteResult.Written::class.java)
            assertThat((result as StyleWriteResult.Written).text).isEqualTo(text)
        }
    }

    @Test
    fun assetsAreMeaningfullyDifferentiated() {
        val documents = assetFiles().map { decodeAsset(it).document }
        val byId = documents.associateBy(StyleDocument::id)
        assertThat(documents.toSet()).hasSize(7)

        assertThat(byId.getValue("builtin-compact-list").pages.albums.columns.portrait).isEqualTo(4)
        assertThat(byId.getValue("builtin-large-cover").pages.albums.columns.portrait).isEqualTo(2)
        val tablet = byId.getValue("builtin-tablet-wide")
        assertThat(tablet.pages.albums.columns.landscape).isEqualTo(8)
        assertThat(tablet.app.cornerScale).isEqualTo(CornerRoundness.LARGE)

        val artwork = byId.getValue("builtin-player-artwork").nowPlaying.background
        assertThat(artwork).isNotNull()
        assertThat(artwork!!.layers.map { it.kind }).containsExactly(
            BackdropLayerKind.ALBUM_ART,
            BackdropLayerKind.BLUR,
            BackdropLayerKind.GRADIENT,
        ).inOrder()

        val minimal = byId.getValue("builtin-player-minimal")
        assertThat(minimal.nowPlaying.compact.blocks).hasSize(3)
        assertThat(minimal.nowPlaying.expanded.portrait.progress.appearance)
            .isEqualTo(app.omnisonic.core.model.style.ProgressAppearance.HAIRLINE)

        val queue = byId.getValue("builtin-player-queue")
        assertThat(queue.nowPlaying.expanded.portrait.blocks.map { it.kind })
            .containsExactly(
                NowPlayingBlockKind.ARTWORK,
                NowPlayingBlockKind.TITLE,
                NowPlayingBlockKind.SUBTITLE,
                NowPlayingBlockKind.QUEUE,
                NowPlayingBlockKind.PROGRESS,
                NowPlayingBlockKind.CONTROLS,
                NowPlayingBlockKind.LYRICS,
            ).inOrder()
    }
}
