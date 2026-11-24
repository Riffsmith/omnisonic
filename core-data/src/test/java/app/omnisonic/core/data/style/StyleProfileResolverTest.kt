package app.omnisonic.core.data.style

import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.DarkMode
import app.omnisonic.core.model.style.StyleApp
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StyleProfileResolverTest {

    @TempDir
    lateinit var tempDir: File

    private val warnings = mutableListOf<String>()

    private val logger = OmnisonicLogger(
        LogSink { level, _, message, _ ->
            if (level >= LogLevel.WARN) warnings += message
        },
    )

    private val baseDocument = StyleDocument(
        id = "default",
        name = "Default",
        app = StyleApp(
            darkMode = DarkMode.DARK,
            cornerScale = CornerRoundness.LARGE,
        ),
    )

    private fun baseText(): String =
        (StyleWriter.write(baseDocument) as StyleWriteResult.Written).text

    private fun repository(assets: Map<String, String> = mapOf("default.json" to baseText())) =
        StyleRepository(
            stylesDir = File(tempDir, StyleRepository.STYLES_DIRECTORY),
            assets = MapStyleAssets(assets),
            logger = logger,
        )

    private fun resolver(repository: StyleRepository = repository()) =
        StyleProfileResolver(repository, "default", logger)

    private fun store(id: String, text: String): File =
        File(tempDir, "${StyleRepository.STYLES_DIRECTORY}/$id${StyleRepository.FILE_SUFFIX}").apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    private fun profileText(id: String = "mine", app: String = """{"cornerScale":"MEDIUM"}""") = """
        {
          "schemaVersion": 1,
          "id": "$id",
          "name": "Mine",
          "app": $app,
          "home": {},
          "pages": {},
          "nowPlaying": {}
        }
    """.trimIndent() + "\n"

    private fun resolved(state: StyleProfileState): StyleProfileState.Resolved =
        state as StyleProfileState.Resolved

    @Test
    fun `an absent key in the active profile falls through to the built-in`() {
        store("mine", profileText())

        val state = resolved(resolver().resolve("mine"))

        assertThat(state.fellBack).isFalse()
        assertThat(state.baseId).isEqualTo("default")
        assertThat(state.document.id).isEqualTo("mine")
        assertThat(state.document.app.cornerScale).isEqualTo(CornerRoundness.MEDIUM)
        assertThat(state.document.app.darkMode).isEqualTo(DarkMode.DARK)
        assertThat(state.warnings).isEmpty()
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `a corrupt active file falls back with exactly one warning and keeps the file`() {
        val corrupt = store("mine", "{ not json")

        val state = resolved(resolver().resolve("mine"))

        assertThat(state.fellBack).isTrue()
        assertThat(state.document).isEqualTo(baseDocument)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("mine")
        assertThat(corrupt.readText()).isEqualTo("{ not json")
        assertThat(corrupt.exists()).isTrue()
    }

    @Test
    fun `an invalid value in the active profile falls back and keeps the file`() {
        val invalid = store("mine", profileText(app = """{"darkMode":"PURPLE"}"""))

        val state = resolved(resolver().resolve("mine"))

        assertThat(state.fellBack).isTrue()
        assertThat(state.document).isEqualTo(baseDocument)
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("app.darkMode")
        assertThat(invalid.exists()).isTrue()
    }

    @Test
    fun `an empty styles directory falls back to the built-in`() {
        val state = resolved(resolver().resolve("mine"))

        assertThat(state.fellBack).isTrue()
        assertThat(state.document.name).isEqualTo("Default")
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `an unset active id resolves the default quietly`() {
        val state = resolved(resolver().resolve(null))

        assertThat(state.fellBack).isFalse()
        assertThat(state.document).isEqualTo(baseDocument)
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `a profile declaring another id is not applied`() {
        store("mine", profileText(id = "other"))

        val state = resolved(resolver().resolve("mine"))

        assertThat(state.fellBack).isTrue()
        assertThat(state.document).isEqualTo(baseDocument)
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `a missing default built-in leaves the style unavailable`() {
        val state = resolver(repository(assets = emptyMap())).resolve(null)

        assertThat(state).isInstanceOf(StyleProfileState.Unavailable::class.java)
        assertThat((state as StyleProfileState.Unavailable).firstMessage).contains("default")
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `a default-id shadow declaring another id is unavailable`() {
        val shadow = store("default", profileText(id = "other"))

        val state = resolver().resolve(null)

        assertThat(state).isInstanceOf(StyleProfileState.Unavailable::class.java)
        assertThat((state as StyleProfileState.Unavailable).firstMessage)
            .contains("does not match the file name 'default'")
        assertThat(warnings).hasSize(1)
        assertThat(shadow.exists()).isTrue()
    }

    @Test
    fun `a mismatched default shadow also blocks a fallback`() {
        store("default", profileText(id = "other"))
        store("mine", profileText(app = """{"darkMode":"PURPLE"}"""))

        val state = resolver().resolve("mine")

        assertThat(state).isInstanceOf(StyleProfileState.Unavailable::class.java)
        assertThat((state as StyleProfileState.Unavailable).firstMessage)
            .contains("does not match the file name 'default'")
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `an asset enumeration failure makes the default typed unavailable`() {
        val failing = StyleRepository(
            stylesDir = File(tempDir, StyleRepository.STYLES_DIRECTORY),
            assets = object : StyleAssetSource {
                override fun names(): List<String> =
                    throw SecurityException("asset index is not readable")

                override fun read(name: String): String? = error("no asset should be read")
            },
            logger = logger,
        )

        val state = resolver(failing).resolve(null)

        assertThat(state).isInstanceOf(StyleProfileState.Unavailable::class.java)
        assertThat((state as StyleProfileState.Unavailable).firstMessage).contains("default")
        assertThat(warnings).hasSize(2)
    }

    private class MapStyleAssets(private val contents: Map<String, String>) : StyleAssetSource {

        override fun names(): List<String> = contents.keys.toList()

        override fun read(name: String): String? = contents[name]
    }
}
