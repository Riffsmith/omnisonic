package app.omnisonic.core.model.style

import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.io.IOException
import java.math.BigDecimal

/**
 * Canonical v1 writer: a document becomes normalized text with a fixed key
 * order per section, stable number formatting, two-space indent and a single
 * trailing newline (`docs/omnisonic/style/style-schema.md` §Normalized form).
 *
 * Every value is written from the typed model, so keys the model does not own
 * cannot be passed through. The text is re-decoded before it is returned: the
 * writer never emits something this build's decoder would reject, and a
 * document it cannot represent fails as a whole instead of partly writing.
 */
object StyleWriter {

    private const val INDENT = "  "

    fun write(document: StyleDocument): StyleWriteResult {
        val text = try {
            encode(document)
        } catch (failure: UnrepresentableValue) {
            return StyleWriteResult.Failed(
                listOf(StyleIssue(STYLE_ROOT_PATH, "unrepresentable document: ${failure.message}")),
            )
        } catch (failure: IOException) {
            return StyleWriteResult.Failed(
                listOf(StyleIssue(STYLE_ROOT_PATH, "unwritable document: ${failure.message}")),
            )
        }
        return when (val decoded = StyleDecoder.decode(text)) {
            is StyleDecodeResult.Rejected -> StyleWriteResult.Failed(decoded.errors)
            is StyleDecodeResult.Decoded -> StyleWriteResult.Written(text)
        }
    }

    private fun encode(document: StyleDocument): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.setIndent(INDENT)
        // A null font family means the platform default and a null background
        // means the resolver's default stack, so the value must be written.
        writer.setSerializeNulls(true)
        writer.writeDocument(document)
        writer.close()
        return buffer.readUtf8().trimEnd('\n') + "\n"
    }

    private fun JsonWriter.writeDocument(document: StyleDocument) {
        writeObject {
            name("schemaVersion").number(document.schemaVersion)
            name("id").value(document.id)
            name("name").value(document.name)
            name("app").writeApp(document.app)
            name("home").writeHome(document.home)
            name("pages").writePages(document.pages)
            name("nowPlaying").writeNowPlaying(document.nowPlaying)
        }
    }

    private fun JsonWriter.writeApp(app: StyleApp) {
        writeObject {
            name("colorSource").value(app.colorSource.name)
            name("seedColor").value(app.seedColor)
            name("darkMode").value(app.darkMode.name)
            name("colorPalette").value(app.colorPalette.name)
            name("colorContrast").value(app.colorContrast.name)
            name("cornerScale").value(app.cornerScale.name)
            name("fontFamilies").writeObject {
                name("title").nullableString(app.fontFamilies.title)
                name("body").nullableString(app.fontFamilies.body)
                name("lyrics").nullableString(app.fontFamilies.lyrics)
                name("nav").nullableString(app.fontFamilies.nav)
            }
            name("effects").writeObject {
                name("quality").value(app.effects.quality.name)
                name("progressiveBlur").value(app.effects.progressiveBlur.name)
                name("overlayAlpha").number(app.effects.overlayAlpha)
                name("legibilityShield").value(app.effects.legibilityShield)
                name("artColorFollowing").value(app.effects.artColorFollowing)
                name("audioReactive").value(app.effects.audioReactive)
                name("tiltParallax").value(app.effects.tiltParallax)
                name("tiltParallaxMaxOffsetDp").number(app.effects.tiltParallaxMaxOffsetDp)
            }
        }
    }

    private fun JsonWriter.writeHome(home: StyleHome) {
        writeObject {
            name("sections").writeArray {
                home.sections.forEach { entry ->
                    writeObject {
                        name("section").value(entry.section.name)
                        name("visible").value(entry.visible)
                    }
                }
            }
        }
    }

    private fun JsonWriter.writePages(pages: StylePages) {
        writeObject {
            name("albums").writeListLayout(pages.albums)
            name("artists").writeListLayout(pages.artists)
            name("genres").writeListLayout(pages.genres)
            name("songs").writeListLayout(pages.songs)
            name("playlists").writeListLayout(pages.playlists)
        }
    }

    private fun JsonWriter.writeListLayout(layout: StyleListLayout) {
        writeObject {
            name("columns").writeObject {
                name("portrait").number(layout.columns.portrait)
                name("landscape").number(layout.columns.landscape)
            }
            name("spacingDp").writeObject {
                name("portrait").number(layout.spacingDp.portrait)
                name("landscape").number(layout.spacingDp.landscape)
            }
        }
    }

    private fun JsonWriter.writeNowPlaying(nowPlaying: StyleNowPlaying) {
        writeObject {
            name("compact").writePlayer(
                nowPlaying.compact.blocks,
                nowPlaying.compact.buttons,
                nowPlaying.compact.text,
                nowPlaying.compact.progress,
            )
            name("expanded").writeObject {
                name("portrait").writePlayer(
                    nowPlaying.expanded.portrait.blocks,
                    nowPlaying.expanded.portrait.buttons,
                    nowPlaying.expanded.portrait.text,
                    nowPlaying.expanded.portrait.progress,
                )
                name("landscape").writePlayer(
                    nowPlaying.expanded.landscape.blocks,
                    nowPlaying.expanded.landscape.buttons,
                    nowPlaying.expanded.landscape.text,
                    nowPlaying.expanded.landscape.progress,
                )
            }
            name("background").writeBackdrop(nowPlaying.background)
        }
    }

    private fun JsonWriter.writePlayer(
        blocks: List<NowPlayingBlock>,
        buttons: List<StyleControlButton>,
        text: StyleTextBlocks,
        progress: StyleProgress,
    ) {
        writeObject {
            name("blocks").writeArray {
                blocks.forEach { block ->
                    writeObject {
                        name("kind").value(block.kind.name)
                        name("visible").value(block.visible)
                    }
                }
            }
            name("buttons").writeArray {
                buttons.forEach { button ->
                    writeObject {
                        name("action").value(button.action.name)
                        name("style").value(button.style.name)
                        name("size").value(button.size.name)
                    }
                }
            }
            name("text").writeObject {
                name("title").writeTextBlock(text.title)
                name("subtitle").writeTextBlock(text.subtitle)
            }
            name("progress").writeObject {
                name("appearance").value(progress.appearance.name)
                name("heightDp").number(progress.heightDp)
                name("showRemainingTime").value(progress.showRemainingTime)
            }
        }
    }

    private fun JsonWriter.writeTextBlock(block: StyleTextBlock) {
        writeObject {
            name("template").value(block.template)
            name("alignment").value(block.alignment.name)
            name("maxLines").number(block.maxLines)
        }
    }

    private fun JsonWriter.writeBackdrop(backdrop: StyleBackdrop?) {
        if (backdrop == null) {
            nullValue()
            return
        }
        writeObject {
            name("extent").value(backdrop.extent.name)
            name("layers").writeArray {
                backdrop.layers.forEach { layer -> writeLayer(layer) }
            }
        }
    }

    private fun JsonWriter.writeLayer(layer: StyleBackdropLayer) {
        writeObject {
            name("kind").value(layer.kind.name)
            when (layer) {
                is StyleSolidBackdropLayer -> name("color").value(layer.color.name)
                is StyleGradientBackdropLayer -> {
                    name("anchor").value(layer.anchor.name)
                    name("startColor").value(layer.startColor.name)
                    name("endColor").value(layer.endColor.name)
                    name("artTint").number(layer.artTint)
                }
                is StyleAlbumArtBackdropLayer -> name("scale").number(layer.scale)
                is StyleBlurBackdropLayer -> {
                    name("radiusDp").number(layer.radiusDp)
                    name("extent").value(layer.extent.name)
                }
                is StyleGrainBackdropLayer -> name("density").number(layer.density)
                is StyleVignetteBackdropLayer -> name("strength").number(layer.strength)
                is StyleGradientMeshBackdropLayer -> name("intensity").number(layer.intensity)
            }
            name("alpha").number(layer.alpha)
            name("blend").value(layer.blend.name)
        }
    }

    private inline fun JsonWriter.writeObject(block: JsonWriter.() -> Unit) {
        beginObject()
        block()
        endObject()
    }

    private inline fun JsonWriter.writeArray(block: JsonWriter.() -> Unit) {
        beginArray()
        block()
        endArray()
    }

    private fun JsonWriter.nullableString(value: String?) {
        if (value == null) nullValue() else value(value)
    }

    private fun JsonWriter.number(value: Int) {
        value(value.toLong())
    }

    private fun JsonWriter.number(value: Float) {
        if (!value.isFinite()) throw UnrepresentableValue("non-finite number $value")
        value(BigDecimal(canonicalNumber(value)))
    }

    /**
     * The shortest decimal that reads back as the same `Float`, without
     * insignificant trailing zeros or an exponent, so an integral dp value is
     * written as an integer.
     */
    private fun canonicalNumber(value: Float): String =
        BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    private class UnrepresentableValue(message: String) : Exception(message)
}
