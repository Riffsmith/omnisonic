package app.omnisonic.core.data.style

import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleMigration
import app.omnisonic.core.model.style.StyleMigrationOutcome
import app.omnisonic.core.model.style.StyleMigrator
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StyleRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private val warnings = mutableListOf<String>()

    private val logger = OmnisonicLogger(
        LogSink { level, _, message, _ ->
            if (level >= LogLevel.WARN) warnings += message
        },
    )

    private fun stylesDir(): File = File(tempDir, StyleRepository.STYLES_DIRECTORY)

    private fun repository(
        assets: Map<String, String> = emptyMap(),
        migrator: StyleMigrator = StyleMigrator(),
        replace: (File, File) -> Boolean = { temp, target -> temp.renameTo(target) },
    ) = StyleRepository(
        stylesDir = stylesDir(),
        assets = MapStyleAssets(assets),
        logger = logger,
        migrator = migrator,
        replace = replace,
    )

    private fun repositoryOver(assets: StyleAssetSource) = StyleRepository(
        stylesDir = stylesDir(),
        assets = assets,
        logger = logger,
    )

    private fun styleText(id: String, name: String) = """
        {
          "schemaVersion": 1,
          "id": "$id",
          "name": "$name",
          "app": {},
          "home": {},
          "pages": {},
          "nowPlaying": {}
        }
    """.trimIndent() + "\n"

    private fun storedFile(id: String, text: String) =
        File(stylesDir(), "$id${StyleRepository.FILE_SUFFIX}").apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    private fun loaded(result: StyleReadResult): StyleReadResult.Loaded =
        result as StyleReadResult.Loaded

    @Test
    fun `write then read round-trips the normalized document`() {
        val repository = repository()
        val document = StyleDocument(id = "alpha", name = "Alpha")
        val outcome = repository.write(document)

        assertThat(outcome).isInstanceOf(StyleWriteOutcome.Written::class.java)
        assertThat((outcome as StyleWriteOutcome.Written).text)
            .isEqualTo((StyleWriter.write(document) as StyleWriteResult.Written).text)

        val read = loaded(repository.read("alpha"))
        assertThat(read.document).isEqualTo(document)
        assertThat(read.origin).isEqualTo(StyleOrigin.USER)
        assertThat(read.warnings).isEmpty()
    }

    @Test
    fun `write puts the canonical normalized text on disk`() {
        val repository = repository()
        repository.write(StyleDocument(id = "alpha", name = "Alpha"))

        val text = File(stylesDir(), "alpha.json").readText()
        assertThat(text).startsWith("{\n  \"schemaVersion\": 1,\n  \"id\": \"alpha\",")
        assertThat(text).endsWith("}\n")
        assertThat(text).isEqualTo(
            (StyleWriter.write(StyleDocument(id = "alpha", name = "Alpha")) as StyleWriteResult.Written).text,
        )
    }

    @Test
    fun `a second write replaces the previous document and leaves no temp file`() {
        val repository = repository()
        repository.write(StyleDocument(id = "alpha", name = "First"))
        repository.write(StyleDocument(id = "alpha", name = "Second"))

        assertThat(loaded(repository.read("alpha")).document.name).isEqualTo("Second")
        assertThat(stylesDir().listFiles()!!.map { it.name }).containsExactly("alpha.json")
    }

    @Test
    fun `a failed replace keeps the previous file intact`() {
        repository().write(StyleDocument(id = "alpha", name = "First"))
        val failing = repository(replace = { _, _ -> false })

        val outcome = failing.write(StyleDocument(id = "alpha", name = "Second"))

        assertThat(outcome).isInstanceOf(StyleWriteOutcome.Unwritable::class.java)
        assertThat(loaded(failing.read("alpha")).document.name).isEqualTo("First")
        assertThat(stylesDir().listFiles()!!.map { it.name }).containsExactly("alpha.json")
    }

    @Test
    fun `leftover temp files are ignored by read and list`() {
        val repository = repository()
        repository.write(StyleDocument(id = "alpha", name = "Alpha"))
        File(stylesDir(), "alpha.json.tmp").writeText("{ not json")
        File(stylesDir(), "orphan.json.tmp").writeText("{ not json")

        assertThat(loaded(repository.read("alpha")).document.name).isEqualTo("Alpha")
        assertThat(repository.list()).containsExactly(
            StyleSummary("alpha", "Alpha", StyleOrigin.USER),
        )
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `an unreadable file surfaces a typed failure`() {
        File(stylesDir(), "locked.json").mkdirs()
        val repository = repository()

        val result = repository.read("locked")

        assertThat(result).isInstanceOf(StyleReadResult.Unreadable::class.java)
        assertThat((result as StyleReadResult.Unreadable).detail).isNotEmpty()
    }

    @Test
    fun `delete removes only the requested id`() {
        val repository = repository()
        repository.write(StyleDocument(id = "alpha", name = "Alpha"))
        repository.write(StyleDocument(id = "beta", name = "Beta"))

        assertThat(repository.delete("alpha")).isEqualTo(StyleDeleteOutcome.Deleted("alpha"))

        assertThat(repository.read("alpha")).isEqualTo(StyleReadResult.NotFound("alpha"))
        assertThat(loaded(repository.read("beta")).document.name).isEqualTo("Beta")
        assertThat(stylesDir().listFiles()!!.map { it.name }).containsExactly("beta.json")
        assertThat(repository.delete("missing")).isEqualTo(StyleDeleteOutcome.NotFound("missing"))
    }

    @Test
    fun `listing skips an unparsable file with exactly one warning`() {
        val repository = repository()
        repository.write(StyleDocument(id = "alpha", name = "Alpha"))
        storedFile("broken", "{ \"schemaVersion\": 1,")
        File(stylesDir(), "notes.txt").writeText("ignored")

        assertThat(repository.list().map { it.id }).containsExactly("alpha")
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("broken.json")
    }

    @Test
    fun `listing surfaces a damaged user file with its path while list still skips it`() {
        val repository = repository()
        val file = storedFile("broken", "{ definitely not a style\n")

        val damaged = repository.listing().single() as StyleListEntry.Damaged

        assertThat(damaged.id).isEqualTo("broken")
        assertThat(damaged.name).isEqualTo("broken")
        assertThat(damaged.origin).isEqualTo(StyleOrigin.USER)
        assertThat(damaged.path).isEqualTo(file.absolutePath)
        assertThat(damaged.detail).startsWith(file.absolutePath)
        assertThat(damaged.detail).contains(": ")
        assertThat(damaged.modifiedAt).isNotNull()

        assertThat(repository.list()).isEmpty()
        assertThat(repository.read("broken")).isInstanceOf(StyleReadResult.Rejected::class.java)
        assertThat(warnings).isNotEmpty()
    }

    @Test
    fun `listing keeps list order and still skips a damaged built-in with one warning`() {
        val repository = repository(
            assets = mapOf(
                "alpha.json" to styleText("alpha", "Alpha"),
                "broken.json" to "{",
            ),
        )
        storedFile("mine", styleText("mine", "Mine"))

        val entries = repository.listing()

        assertThat(entries.map { it.id }).containsExactly("alpha", "mine").inOrder()
        assertThat(entries.map { it.name }).containsExactly("Alpha", "Mine").inOrder()
        assertThat(entries.map { it.origin })
            .containsExactly(StyleOrigin.BUILT_IN, StyleOrigin.USER)
            .inOrder()
        assertThat(entries.filterIsInstance<StyleListEntry.Damaged>()).isEmpty()
        assertThat(repository.list().map { it.id }).containsExactly("alpha", "mine").inOrder()
        assertThat(warnings).isNotEmpty()
    }

    @Test
    fun `readRaw returns the stored bytes unchanged and reports absence separately`() {
        val repository = repository()
        val text = "{\n  \"schemaVersion\": 0,\n  \"id\": \"raw\",\n  \"surprise\": true\n}\n"
        val file = storedFile("raw", text)

        val loaded = repository.readRaw("raw") as StyleRawResult.Loaded

        assertThat(loaded.id).isEqualTo("raw")
        assertThat(loaded.bytes).isEqualTo(text.toByteArray())
        assertThat(loaded.path).isEqualTo(file.absolutePath)
        assertThat(file.readText()).isEqualTo(text)
        assertThat(repository.readRaw("missing"))
            .isEqualTo(StyleRawResult.NotFound("missing"))
        assertThat(repository.readRaw("../escape"))
            .isEqualTo(StyleRawResult.InvalidId("../escape"))
    }

    @Test
    fun `an id-shaped path cannot escape the styles directory`() {
        val repository = repository()
        val hostile = listOf("../escape", "..", "/tmp/evil", "a/b", "..\\evil", "", ".")

        hostile.forEach { id ->
            assertThat(repository.read(id)).isEqualTo(StyleReadResult.InvalidId(id))
            assertThat(repository.write(StyleDocument(id = id, name = "Evil")))
                .isEqualTo(StyleWriteOutcome.InvalidId(id))
            assertThat(repository.delete(id)).isEqualTo(StyleDeleteOutcome.InvalidId(id))
        }

        assertThat(File(tempDir, "escape.json").exists()).isFalse()
        assertThat(stylesDir().exists()).isFalse()
    }

    @Test
    fun `built-in assets are listed and read`() {
        val repository = repository(assets = mapOf("builtin-one.json" to styleText("beta", "Beta")))

        assertThat(repository.list()).containsExactly(
            StyleSummary("beta", "Beta", StyleOrigin.BUILT_IN),
        )
        val read = loaded(repository.read("beta"))
        assertThat(read.origin).isEqualTo(StyleOrigin.BUILT_IN)
        assertThat(read.document.name).isEqualTo("Beta")
        assertThat(repository.read("missing")).isEqualTo(StyleReadResult.NotFound("missing"))
    }

    @Test
    fun `a user file shadows a built-in with the same id`() {
        val repository = repository(
            assets = mapOf("builtin-one.json" to styleText("beta", "Built-in Beta")),
        )
        repository.write(StyleDocument(id = "beta", name = "Mine"))

        assertThat(repository.list()).containsExactly(StyleSummary("beta", "Mine", StyleOrigin.USER))
        assertThat(loaded(repository.read("beta")).origin).isEqualTo(StyleOrigin.USER)
    }

    @Test
    fun `duplicate built-in ids keep the first asset in name order`() {
        val repository = repository(
            assets = mapOf(
                "a.json" to styleText("shared", "First"),
                "b.json" to styleText("shared", "Second"),
            ),
        )

        assertThat(repository.list()).containsExactly(
            StyleSummary("shared", "First", StyleOrigin.BUILT_IN),
        )
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `a file declaring another id is rejected and skipped`() {
        storedFile("alpha", styleText("beta", "Beta"))
        val repository = repository()

        val read = repository.read("alpha")

        assertThat(read).isInstanceOf(StyleReadResult.Rejected::class.java)
        assertThat((read as StyleReadResult.Rejected).firstMessage).contains("does not match the file name")
        assertThat(repository.list()).isEmpty()
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `migrated text is decoded after the chain runs`() {
        storedFile("alpha", styleText("alpha", "Alpha"))
        val migrator = StyleMigrator(
            migrations = listOf(
                StyleMigration(1, 2) { tree -> StyleMigrationOutcome.Migrated(tree) },
            ),
            targetVersion = 2,
        )

        val read = repository(migrator = migrator).read("alpha")

        assertThat(read).isInstanceOf(StyleReadResult.Rejected::class.java)
        assertThat((read as StyleReadResult.Rejected).firstMessage)
            .contains("unsupported schema version 2")
    }

    @Test
    fun `a corrupt user shadow removes the built-in summary`() {
        val repository = repository(
            assets = mapOf("builtin-one.json" to styleText("beta", "Built-in Beta")),
        )
        storedFile("beta", "{ \"schemaVersion\": 1,")

        assertThat(repository.read("beta")).isInstanceOf(StyleReadResult.Rejected::class.java)
        assertThat(repository.list()).isEmpty()
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("beta.json")
    }

    @Test
    fun `a user shadow with another id removes the built-in summary`() {
        val repository = repository(
            assets = mapOf("builtin-one.json" to styleText("beta", "Built-in Beta")),
        )
        storedFile("beta", styleText("other", "Other"))

        assertThat(repository.read("beta")).isInstanceOf(StyleReadResult.Rejected::class.java)
        assertThat(repository.list()).isEmpty()
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `a document the writer rejects is never written`() {
        val outcome = repository().write(StyleDocument(id = "alpha", name = " "))

        assertThat(outcome).isInstanceOf(StyleWriteOutcome.Rejected::class.java)
        assertThat((outcome as StyleWriteOutcome.Rejected).firstMessage).contains("name")
        assertThat(stylesDir().exists()).isFalse()
    }

    @Test
    fun `an enumeration failure keeps user styles listed with one warning`() {
        storedFile("alpha", styleText("alpha", "Alpha"))
        val repository = repositoryOver(
            LambdaStyleAssets(
                enumerate = { throw IOException("damaged asset index") },
                content = { error("no asset is read when enumeration fails") },
            ),
        )

        assertThat(repository.list()).containsExactly(
            StyleSummary("alpha", "Alpha", StyleOrigin.USER),
        )
        assertThat(repository.read("alpha")).isInstanceOf(StyleReadResult.Loaded::class.java)
        assertThat(repository.read("missing")).isEqualTo(StyleReadResult.NotFound("missing"))
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("could not be enumerated")
    }

    @Test
    fun `an enumeration security failure keeps user styles listed with one warning`() {
        storedFile("alpha", styleText("alpha", "Alpha"))
        val repository = repositoryOver(
            LambdaStyleAssets(
                enumerate = { throw SecurityException("asset index is not readable") },
                content = { error("no asset is read when enumeration fails") },
            ),
        )

        assertThat(repository.list()).containsExactly(
            StyleSummary("alpha", "Alpha", StyleOrigin.USER),
        )
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("could not be enumerated")
    }

    @Test
    fun `a built-in read that returns null is skipped with one warning`() {
        val repository = repositoryOver(
            LambdaStyleAssets(
                enumerate = { listOf("gone.json", "builtin-one.json") },
                content = { name -> if (name == "gone.json") null else styleText("beta", "Beta") },
            ),
        )

        assertThat(repository.list()).containsExactly(
            StyleSummary("beta", "Beta", StyleOrigin.BUILT_IN),
        )
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("gone.json")
    }

    @Test
    fun `a built-in read that throws IOException is skipped with one warning`() {
        val repository = repositoryOver(
            LambdaStyleAssets(
                enumerate = { listOf("broken.json", "builtin-one.json") },
                content = { name ->
                    if (name == "broken.json") throw IOException("damaged asset") else styleText("beta", "Beta")
                },
            ),
        )

        assertThat(loaded(repository.read("beta")).document.name).isEqualTo("Beta")
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("broken.json")
    }

    @Test
    fun `a built-in read that throws SecurityException is skipped with one warning`() {
        val repository = repositoryOver(
            LambdaStyleAssets(
                enumerate = { listOf("broken.json", "builtin-one.json") },
                content = { name ->
                    if (name == "broken.json") throw SecurityException("asset is not readable") else styleText("beta", "Beta")
                },
            ),
        )

        assertThat(loaded(repository.read("beta")).document.name).isEqualTo("Beta")
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("broken.json")
    }

    @Test
    fun `one failing built-in read leaves the remaining built-ins and user styles usable`() {
        storedFile("alpha", styleText("alpha", "Alpha"))
        val repository = repositoryOver(
            LambdaStyleAssets(
                enumerate = { listOf("a.json", "b.json", "c.json") },
                content = { name ->
                    when (name) {
                        "a.json" -> styleText("first", "First")
                        "b.json" -> throw IOException("damaged asset")
                        else -> styleText("third", "Third")
                    }
                },
            ),
        )

        assertThat(repository.list()).containsExactly(
            StyleSummary("first", "First", StyleOrigin.BUILT_IN),
            StyleSummary("third", "Third", StyleOrigin.BUILT_IN),
            StyleSummary("alpha", "Alpha", StyleOrigin.USER),
        )
        assertThat(repository.read("first")).isInstanceOf(StyleReadResult.Loaded::class.java)
        assertThat(repository.read("broken")).isEqualTo(StyleReadResult.NotFound("broken"))
        assertThat(warnings).hasSize(1)
    }

    private class LambdaStyleAssets(
        private val enumerate: () -> List<String>,
        private val content: (String) -> String?,
    ) : StyleAssetSource {

        override fun names(): List<String> = enumerate()

        override fun read(name: String): String? = content(name)
    }

    private class MapStyleAssets(private val contents: Map<String, String>) : StyleAssetSource {
        override fun names(): List<String> = contents.keys.toList()

        override fun read(name: String): String? = contents[name]
    }
}
