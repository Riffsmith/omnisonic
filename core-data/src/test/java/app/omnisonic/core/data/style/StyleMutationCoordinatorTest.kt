package app.omnisonic.core.data.style

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.DarkMode
import app.omnisonic.core.model.style.StyleApp
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleEffects
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The shared mutation boundary: one ordered path per command, revision checks,
 * truthful typed failures and non-destructive collision handling
 * (`docs/omnisonic/style/style-loader.md` §6, D-108).
 */
class StyleMutationCoordinatorTest {

    @TempDir
    lateinit var tempDir: File

    private val warnings = mutableListOf<String>()

    private val logger = OmnisonicLogger(
        LogSink { level, _, message, _ ->
            if (level >= LogLevel.WARN) warnings += message
        },
    )

    private var fileCounter = 0

    @AfterEach
    fun resetCounter() {
        fileCounter = 0
    }

    private val baseDocument = StyleDocument(
        id = "default",
        name = "Default",
        app = StyleApp(
            darkMode = DarkMode.DARK,
            cornerScale = CornerRoundness.MEDIUM,
        ),
    )

    private fun stylesDir(): File = File(tempDir, StyleRepository.STYLES_DIRECTORY)

    private fun baseText(): String =
        (StyleWriter.write(baseDocument) as StyleWriteResult.Written).text

    private fun userDocument(
        id: String = "mine",
        name: String = "Mine",
        darkMode: DarkMode = DarkMode.LIGHT,
        cornerScale: CornerRoundness = CornerRoundness.EXTRA_LARGE,
        overlayAlpha: Float = 0.8f,
    ): StyleDocument = StyleDocument(
        id = id,
        name = name,
        app = StyleApp(
            darkMode = darkMode,
            cornerScale = cornerScale,
            effects = StyleEffects(overlayAlpha = overlayAlpha),
        ),
    )

    private fun derivationFile(): File = File(tempDir, "derivations-${fileCounter++}.json")

    private class Graph(
        val repository: StyleRepository,
        val derivations: StyleDerivationStore,
        val store: StyleProfileStore,
        val coordinator: StyleMutationCoordinator,
    )

    private fun graph(
        replace: (temp: File, target: File) -> Boolean = { temp, target -> temp.renameTo(target) },
        derivations: StyleDerivationStore = FileStyleDerivationStore(derivationFile()),
        dataStore: DataStore<Preferences> = MutableDataStore(),
        clock: () -> Long = { 1_700_000_000_000L },
        newId: StyleIdGenerator = StyleIdGenerator { baseId, now, attempt ->
            "copy-$baseId-$now-$attempt"
        },
    ): Graph {
        val repository = StyleRepository(
            stylesDir = stylesDir(),
            assets = MapStyleAssets(mapOf("default.json" to baseText())),
            logger = logger,
            replace = replace,
        )
        val store = StyleProfileStore(
            dataStore = dataStore,
            repository = repository,
            resolver = StyleProfileResolver(repository, "default", logger),
            logger = logger,
        )
        return Graph(
            repository = repository,
            derivations = derivations,
            store = store,
            coordinator = StyleMutationCoordinator(
                repository = repository,
                profileStore = store,
                derivations = derivations,
                logger = logger,
                defaultStyleId = "default",
                clock = clock,
                newId = newId,
            ),
        )
    }

    private suspend fun createMine(graph: Graph, activate: Boolean = false): StyleMutationResult.Saved =
        graph.coordinator.create(userDocument(), "default", activate) as StyleMutationResult.Saved

    private fun storedFile(id: String): File =
        File(stylesDir(), "$id${StyleRepository.FILE_SUFFIX}")

    private fun storedNames(): List<String> =
        stylesDir().listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test
    fun `create writes the style, records its derivation base and can activate`(): Unit = runBlocking {
        val graph = graph()

        val saved = createMine(graph, activate = true)

        assertThat(saved.id).isEqualTo("mine")
        assertThat(saved.activated).isTrue()
        assertThat(storedFile("mine").isFile).isTrue()
        assertThat(graph.derivations.baseOf("mine")).isEqualTo("default")
        assertThat(graph.store.activeId.first()).isEqualTo("mine")
    }

    @Test
    fun `create refuses an occupied id and overwrites nothing`(): Unit = runBlocking {
        val graph = graph()
        createMine(graph)

        val result = graph.coordinator.create(userDocument(name = "Other"), "default")

        assertThat(result).isInstanceOf(StyleMutationResult.Rejected::class.java)
        val read = graph.repository.read("mine") as StyleReadResult.Loaded
        assertThat(read.document.name).isEqualTo("Mine")
    }

    @Test
    fun `rapid alternating field writes preserve both fields and end at the latest`(): Unit = runBlocking {
        val graph = graph()
        var saved = createMine(graph)

        repeat(20) { index ->
            val overlay = 0.1f + index * 0.01f
            saved = graph.coordinator.update("mine", saved.revision) { document ->
                document.copy(
                    app = document.app.copy(
                        effects = document.app.effects.copy(overlayAlpha = overlay),
                    ),
                )
            } as StyleMutationResult.Saved
            val corner = if (index % 2 == 0) CornerRoundness.LARGE else CornerRoundness.EXTRA_LARGE
            saved = graph.coordinator.update("mine", saved.revision) { document ->
                document.copy(app = document.app.copy(cornerScale = corner))
            } as StyleMutationResult.Saved

            assertThat(saved.document.app.effects.overlayAlpha).isEqualTo(overlay)
            assertThat(saved.document.app.cornerScale).isEqualTo(corner)
        }

        val committed = (graph.repository.read("mine") as StyleReadResult.Loaded).document
        assertThat(committed).isEqualTo(saved.document)
        assertThat(committed.app.effects.overlayAlpha).isEqualTo(0.1f + 19 * 0.01f)
        assertThat(committed.app.cornerScale).isEqualTo(CornerRoundness.EXTRA_LARGE)
        assertThat(storedNames()).containsExactly("mine.json")
    }

    @Test
    fun `a concurrent update with one captured revision commits once and refuses the stale one`(): Unit =
        runBlocking {
            val graph = graph()
            val created = createMine(graph)

            val first = async(Dispatchers.IO) {
                graph.coordinator.update("mine", created.revision) { it.copy(name = "One") }
            }
            val second = async(Dispatchers.IO) {
                graph.coordinator.update("mine", created.revision) { it.copy(name = "Two") }
            }
            val results = listOf(first.await(), second.await())

            assertThat(results.count { it is StyleMutationResult.Saved }).isEqualTo(1)
            assertThat(results.count { it is StyleMutationResult.StaleStyle }).isEqualTo(1)
            val committed = (graph.repository.read("mine") as StyleReadResult.Loaded).document
            assertThat(committed.name).isEqualTo(
                (results.first { it is StyleMutationResult.Saved } as StyleMutationResult.Saved).document.name,
            )
        }

    @Test
    fun `a stale revision is rejected and the committed document stays`(): Unit = runBlocking {
        val graph = graph()
        val created = createMine(graph)
        graph.repository.write(userDocument(name = "External"))

        val result = graph.coordinator.update("mine", created.revision) { it.copy(name = "Mine edit") }

        assertThat(result).isInstanceOf(StyleMutationResult.StaleStyle::class.java)
        val stale = result as StyleMutationResult.StaleStyle
        assertThat(stale.actualRevision).isNotEqualTo(created.revision)
        assertThat((graph.repository.read("mine") as StyleReadResult.Loaded).document.name)
            .isEqualTo("External")
    }

    @Test
    fun `an injected replace failure leaves prior bytes, pointer and resolved document`(): Unit =
        runBlocking {
            var failReplace = false
            val graph = graph(
                replace = { temp, target -> if (failReplace) false else temp.renameTo(target) },
            )
            val created = createMine(graph, activate = true)
            val priorText = storedFile("mine").readText()
            failReplace = true

            val result = graph.coordinator.update("mine", created.revision) {
                it.copy(app = it.app.copy(cornerScale = CornerRoundness.DEFAULT))
            }

            assertThat(result).isInstanceOf(StyleMutationResult.Failed::class.java)
            assertThat(storedFile("mine").readText()).isEqualTo(priorText)
            assertThat(graph.store.activeId.first()).isEqualTo("mine")
            assertThat((graph.store.activeProfile.first() as StyleProfileState.Resolved).document)
                .isEqualTo(created.document)
            assertThat(storedNames()).containsExactly("mine.json")
        }

    @Test
    fun `a DataStore switch failure is a typed failure, not a success`(): Unit = runBlocking {
        val graph = graph(dataStore = FailingWriteDataStore())
        createMine(graph)

        val result = graph.coordinator.switch("mine")

        assertThat(result).isInstanceOf(StyleMutationResult.Failed::class.java)
        assertThat((result as StyleMutationResult.Failed).detail).contains("pointer")
    }

    @Test
    fun `a DataStore delete failure reports a partial result and no success shape`(): Unit = runBlocking {
        val active = mutablePreferencesOf(stringPreferencesKey("style.active_id") to "mine")
        val graph = graph(dataStore = FailingWriteDataStore(active))
        createMine(graph)

        val result = graph.coordinator.delete("mine")

        assertThat(result).isInstanceOf(StyleMutationResult.Partial::class.java)
        assertThat((result as StyleMutationResult.Partial).detail).contains("active pointer")
        assertThat(storedFile("mine").exists()).isFalse()
    }

    @Test
    fun `delete of the active style clears the pointer and the derivation entry`(): Unit = runBlocking {
        val graph = graph()
        createMine(graph, activate = true)

        assertThat(graph.coordinator.delete("mine")).isEqualTo(StyleMutationResult.Deleted("mine"))

        assertThat(storedFile("mine").exists()).isFalse()
        assertThat(graph.store.activeId.first()).isNull()
        assertThat(graph.derivations.baseOf("mine")).isNull()
        val resolved = graph.store.activeProfile.first() as StyleProfileState.Resolved
        assertThat(resolved.document).isEqualTo(baseDocument)
    }

    @Test
    fun `an active-id read failure aborts the delete before any file or derivation change`(): Unit =
        runBlocking {
            val dataStore = FailingReadDataStore(
                mutablePreferencesOf(stringPreferencesKey("style.active_id") to "mine"),
            )
            val graph = graph(dataStore = dataStore)
            createMine(graph)

            val result = graph.coordinator.delete("mine")

            assertThat(result).isInstanceOf(StyleMutationResult.Failed::class.java)
            assertThat((result as StyleMutationResult.Failed).detail).contains("nothing was deleted")
            assertThat(storedFile("mine").isFile).isTrue()
            assertThat(graph.derivations.baseOf("mine")).isEqualTo("default")
            assertThat(storedNames()).containsExactly("mine.json")
            assertThat(dataStore.writes).isEqualTo(0)
        }

    @Test
    fun `deleting an inactive style keeps the active pointer and clears only its own entry`(): Unit =
        runBlocking {
            val graph = graph()
            createMine(graph, activate = true)
            graph.coordinator.create(userDocument(id = "other", name = "Other"), "default")

            assertThat(graph.coordinator.delete("other")).isEqualTo(StyleMutationResult.Deleted("other"))

            assertThat(storedFile("other").exists()).isFalse()
            assertThat(graph.store.activeId.first()).isEqualTo("mine")
            assertThat(graph.derivations.baseOf("other")).isNull()
            assertThat(graph.derivations.baseOf("mine")).isEqualTo("default")
        }

    @Test
    fun `recovery delete removes a damaged file and clears the pointer it held`(): Unit =
        runBlocking {
            val graph = graph(
                dataStore = MutableDataStore(
                    mutablePreferencesOf(stringPreferencesKey("style.active_id") to "broken"),
                ),
            )
            val broken = storedFile("broken").apply {
                parentFile?.mkdirs()
                writeText("{ this is not a style\n")
            }
            assertThat(graph.derivations.set("broken", "default")).isTrue()

            assertThat(graph.coordinator.deleteDamaged("broken"))
                .isEqualTo(StyleMutationResult.Deleted("broken"))

            assertThat(broken.exists()).isFalse()
            assertThat(graph.store.activeId.first()).isNull()
            assertThat(graph.derivations.baseOf("broken")).isNull()
            val resolved = graph.store.activeProfile.first() as StyleProfileState.Resolved
            assertThat(resolved.document).isEqualTo(baseDocument)
        }

    @Test
    fun `recovery commands refuse a valid style, an unknown id and an invalid id`(): Unit =
        runBlocking {
            val graph = graph()
            createMine(graph, activate = true)

            assertThat(graph.coordinator.deleteDamaged("mine"))
                .isInstanceOf(StyleMutationResult.Rejected::class.java)
            assertThat(graph.coordinator.resetDamagedToDefault("mine"))
                .isInstanceOf(StyleMutationResult.Rejected::class.java)
            assertThat(storedFile("mine").isFile).isTrue()
            assertThat(graph.derivations.baseOf("mine")).isEqualTo("default")
            assertThat(graph.store.activeId.first()).isEqualTo("mine")

            assertThat(graph.coordinator.deleteDamaged("missing"))
                .isEqualTo(StyleMutationResult.NotFound("missing"))
            assertThat(graph.coordinator.deleteDamaged("../escape"))
                .isInstanceOf(StyleMutationResult.Rejected::class.java)
            assertThat(graph.coordinator.resetDamagedToDefault("missing"))
                .isEqualTo(StyleMutationResult.NotFound("missing"))
        }

    @Test
    fun `reset to default writes the default once under the damaged id and records it as the base`() =
        runBlocking {
            var writes = 0
            val graph = graph(
                replace = { temp, target ->
                    writes += 1
                    temp.renameTo(target)
                },
            )
            val broken = storedFile("broken").apply {
                parentFile?.mkdirs()
                writeText("{ nope")
            }

            val result = graph.coordinator.resetDamagedToDefault("broken")

            val saved = result as StyleMutationResult.Saved
            assertThat(writes).isEqualTo(1)
            assertThat(saved.id).isEqualTo("broken")
            assertThat(saved.derivationBaseId).isEqualTo("default")
            assertThat(saved.document).isEqualTo(baseDocument.copy(id = "broken"))
            val expected = (StyleWriter.write(baseDocument.copy(id = "broken"))
                as StyleWriteResult.Written).text
            assertThat(broken.readText()).isEqualTo(expected)
            assertThat(storedNames()).containsExactly("broken.json")
            assertThat(graph.derivations.baseOf("broken")).isEqualTo("default")
            val reread = graph.repository.read("broken") as StyleReadResult.Loaded
            assertThat(reread.document).isEqualTo(baseDocument.copy(id = "broken"))
        }

    @Test
    fun `raw export returns the exact stored bytes without decoding or writing`(): Unit =
        runBlocking {
            val graph = graph()
            val bytes = byteArrayOf(0x7B, 0x20, 0x41, 0xFF.toByte(), 0x0A)
            val broken = storedFile("broken").apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            val before = storedNames()

            val loaded = graph.coordinator.exportRaw("broken") as StyleRawResult.Loaded

            assertThat(loaded.id).isEqualTo("broken")
            assertThat(loaded.bytes).isEqualTo(bytes)
            assertThat(loaded.path).isEqualTo(broken.absolutePath)
            assertThat(broken.readBytes()).isEqualTo(bytes)
            assertThat(storedNames()).isEqualTo(before)
            assertThat(graph.coordinator.exportRaw("missing"))
                .isEqualTo(StyleRawResult.NotFound("missing"))
            assertThat(graph.coordinator.exportRaw("../escape"))
                .isEqualTo(StyleRawResult.InvalidId("../escape"))
        }

    @Test
    fun `an observation-only pointer read failure still resolves the default built-in`(): Unit =
        runBlocking {
            val graph = graph(dataStore = FailingReadDataStore())
            createMine(graph)

            val resolved = graph.store.activeProfile.first() as StyleProfileState.Resolved

            assertThat(resolved.document).isEqualTo(baseDocument)
        }

    @Test
    fun `a delete racing a write cannot resurrect the file`(): Unit = runBlocking {
        val graph = graph()
        val created = createMine(graph)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val update = async(Dispatchers.IO) {
            graph.coordinator.update("mine", created.revision) { document ->
                entered.complete(Unit)
                runBlocking { gate.await() }
                document.copy(name = "Edited")
            }
        }
        entered.await()
        val delete = async(Dispatchers.IO) { graph.coordinator.delete("mine") }
        delay(200)
        assertThat(delete.isCompleted).isFalse()

        gate.complete(Unit)

        assertThat(update.await()).isInstanceOf(StyleMutationResult.Saved::class.java)
        assertThat(delete.await()).isEqualTo(StyleMutationResult.Deleted("mine"))
        assertThat(storedFile("mine").exists()).isFalse()
        assertThat(graph.derivations.baseOf("mine")).isNull()
    }

    @Test
    fun `a write after a delete is not found and creates nothing`(): Unit = runBlocking {
        val graph = graph()
        val created = createMine(graph)
        graph.coordinator.delete("mine")

        val result = graph.coordinator.update("mine", created.revision) { it.copy(name = "Late") }

        assertThat(result).isEqualTo(StyleMutationResult.NotFound("mine"))
        assertThat(storedFile("mine").exists()).isFalse()
    }

    @Test
    fun `update, rename and reset refuse a built-in id and write no shadow`(): Unit = runBlocking {
        val graph = graph()
        val revision = StyleRevision("observed")

        assertThat(graph.coordinator.update("default", revision) { it.copy(name = "Mine") })
            .isInstanceOf(StyleMutationResult.Rejected::class.java)
        assertThat(graph.coordinator.rename("default", revision, "Mine"))
            .isInstanceOf(StyleMutationResult.Rejected::class.java)
        assertThat(graph.coordinator.reset("default", revision, StyleResetScope.All))
            .isInstanceOf(StyleMutationResult.Rejected::class.java)
        assertThat(graph.coordinator.delete("default"))
            .isInstanceOf(StyleMutationResult.Rejected::class.java)
        assertThat(storedFile("default").exists()).isFalse()
    }

    @Test
    fun `rename changes only the name`(): Unit = runBlocking {
        val graph = graph()
        val created = createMine(graph)

        val renamed = graph.coordinator.rename("mine", created.revision, "Renamed") as
            StyleMutationResult.Saved

        assertThat(renamed.document).isEqualTo(created.document.copy(name = "Renamed"))
        assertThat(renamed.document.id).isEqualTo("mine")
        assertThat(renamed.derivationBaseId).isEqualTo("default")
        assertThat(graph.derivations.baseOf("mine")).isEqualTo("default")
    }

    @Test
    fun `reset restores the derived base at the requested scope`(): Unit = runBlocking {
        val graph = graph()
        var saved = createMine(graph)

        saved = graph.coordinator.reset("mine", saved.revision, StyleResetScope.Field("app.cornerScale"))
            as StyleMutationResult.Saved
        assertThat(saved.document.app.cornerScale).isEqualTo(CornerRoundness.MEDIUM)
        assertThat(saved.document.app.darkMode).isEqualTo(DarkMode.LIGHT)
        assertThat(saved.document.app.effects.overlayAlpha).isEqualTo(0.8f)

        saved = graph.coordinator.reset(
            "mine",
            saved.revision,
            StyleResetScope.Field("app.effects.overlayAlpha"),
        ) as StyleMutationResult.Saved
        assertThat(saved.document.app.effects.overlayAlpha).isEqualTo(0.4f)
        assertThat(saved.document.app.darkMode).isEqualTo(DarkMode.LIGHT)

        saved = graph.coordinator.reset("mine", saved.revision, StyleResetScope.Section(StyleSection.APP))
            as StyleMutationResult.Saved
        assertThat(saved.document.app).isEqualTo(baseDocument.app)

        saved = graph.coordinator.reset("mine", saved.revision, StyleResetScope.All)
            as StyleMutationResult.Saved
        assertThat(saved.document.app).isEqualTo(baseDocument.app)
        assertThat(saved.document.id).isEqualTo("mine")
        assertThat(saved.document.name).isEqualTo("Mine")
    }

    @Test
    fun `an unknown reset path is refused and changes nothing`(): Unit = runBlocking {
        val graph = graph()
        val created = createMine(graph)

        val result = graph.coordinator.reset(
            "mine",
            created.revision,
            StyleResetScope.Field("app.effects.notAField"),
        )

        assertThat(result).isInstanceOf(StyleMutationResult.Rejected::class.java)
        assertThat((graph.repository.read("mine") as StyleReadResult.Loaded).document)
            .isEqualTo(created.document)
    }

    @Test
    fun `two styles may share a name and identity is the id`(): Unit = runBlocking {
        val graph = graph()
        graph.coordinator.create(userDocument(id = "one", name = "Same"), "default")
        val second = graph.coordinator.create(userDocument(id = "two", name = "Same"), "default")
            as StyleMutationResult.Saved

        assertThat(second.savedAsCopy).isFalse()
        assertThat(graph.repository.list().filter { it.name == "Same" }.map { it.id })
            .containsExactly("one", "two")
        assertThat((graph.repository.read("one") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Same")
        assertThat((graph.repository.read("two") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Same")

        assertThat(graph.coordinator.switch("two"))
            .isEqualTo(StyleMutationResult.Switched("two"))
        assertThat(graph.store.activeId.first()).isEqualTo("two")

        assertThat(graph.coordinator.delete("one"))
            .isEqualTo(StyleMutationResult.Deleted("one"))
        assertThat((graph.repository.read("two") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Same")
        assertThat(graph.store.activeId.first()).isEqualTo("two")
    }

    @Test
    fun `duplicate gets a fresh id, copies the document and records the base`(): Unit = runBlocking {
        val graph = graph()
        val created = createMine(graph)

        val copy = graph.coordinator.duplicate("mine") as StyleMutationResult.Saved

        assertThat(copy.id).isNotEqualTo("mine")
        assertThat(copy.savedAsCopy).isTrue()
        assertThat(copy.document).isEqualTo(created.document.copy(id = copy.id))
        assertThat(graph.derivations.baseOf(copy.id)).isEqualTo("default")
        assertThat(storedFile(copy.id).isFile).isTrue()
        assertThat(storedFile("mine").isFile).isTrue()
        assertThat((graph.repository.read(copy.id) as StyleReadResult.Loaded).document)
            .isEqualTo(copy.document)
    }

    @Test
    fun `duplicating a built-in records that built-in as the base`(): Unit = runBlocking {
        val graph = graph()

        val copy = graph.coordinator.duplicate("default") as StyleMutationResult.Saved

        assertThat(copy.id).isNotEqualTo("default")
        assertThat(copy.document.name).isEqualTo("Default")
        assertThat(graph.derivations.baseOf(copy.id)).isEqualTo("default")
        assertThat(storedFile("default").exists()).isFalse()
    }

    @Test
    fun `import keeps a free id, ignores an identical document and copies a conflict`(): Unit =
        runBlocking {
            val graph = graph()
            val incoming = userDocument(id = "incoming", name = "Incoming")

            val first = graph.coordinator.importWrite(incoming) as StyleMutationResult.Saved
            assertThat(first.id).isEqualTo("incoming")
            assertThat(first.savedAsCopy).isFalse()
            assertThat(first.activated).isFalse()
            assertThat(graph.derivations.baseOf("incoming")).isEqualTo("default")
            assertThat(graph.store.activeId.first()).isNull()

            val identical = graph.coordinator.importWrite(incoming) as StyleMutationResult.Saved
            assertThat(identical.unchanged).isTrue()
            assertThat(identical.id).isEqualTo("incoming")

            val conflict = graph.coordinator.importWrite(userDocument(id = "incoming", name = "Different"))
                as StyleMutationResult.Saved
            assertThat(conflict.savedAsCopy).isTrue()
            assertThat(conflict.id).isNotEqualTo("incoming")
            assertThat((graph.repository.read("incoming") as StyleReadResult.Loaded).document.name)
                .isEqualTo("Incoming")
        }

    @Test
    fun `import never shadows a built-in id`(): Unit = runBlocking {
        val graph = graph()

        val identical = graph.coordinator.importWrite(baseDocument) as StyleMutationResult.Saved
        assertThat(identical.unchanged).isTrue()
        assertThat(identical.id).isEqualTo("default")
        assertThat(storedFile("default").exists()).isFalse()

        val different = graph.coordinator.importWrite(baseDocument.copy(name = "Copy of Default"))
            as StyleMutationResult.Saved
        assertThat(different.savedAsCopy).isTrue()
        assertThat(different.id).isNotEqualTo("default")
        assertThat((graph.repository.read(different.id) as StyleReadResult.Loaded).document.name)
            .isEqualTo("Copy of Default")
        assertThat(storedFile("default").exists()).isFalse()
    }

    @Test
    fun `import and restore share one lock`(): Unit = runBlocking {
        val derivations = GatedDerivationStore()
        val graph = graph(derivations = derivations)

        val importJob = async(Dispatchers.IO) {
            graph.coordinator.importWrite(userDocument(id = "imported"))
        }
        derivations.firstSetStarted.await()
        val restoreJob = async(Dispatchers.IO) {
            graph.coordinator.restoreWrite(userDocument(id = "restored"), "default", activate = false)
        }
        delay(200)
        assertThat(restoreJob.isCompleted).isFalse()
        assertThat(storedFile("restored").exists()).isFalse()

        derivations.release.complete(Unit)

        assertThat(importJob.await()).isInstanceOf(StyleMutationResult.Saved::class.java)
        assertThat(restoreJob.await()).isInstanceOf(StyleMutationResult.Saved::class.java)
        assertThat(storedFile("imported").isFile).isTrue()
        assertThat(storedFile("restored").isFile).isTrue()
    }

    @Test
    fun `restore writes then activates, and a pointer failure is saved inactive`(): Unit = runBlocking {
        val working = graph()
        val restored = working.coordinator.restoreWrite(
            userDocument(id = "restored"),
            "default",
            activate = true,
        ) as StyleMutationResult.Saved
        assertThat(restored.activated).isTrue()
        assertThat(working.store.activeId.first()).isEqualTo("restored")

        val failing = graph(dataStore = FailingWriteDataStore())
        val partial = failing.coordinator.restoreWrite(
            userDocument(id = "saved-inactive"),
            "default",
            activate = true,
        )

        assertThat(partial).isInstanceOf(StyleMutationResult.Partial::class.java)
        val detail = partial as StyleMutationResult.Partial
        assertThat(detail.committed?.id).isEqualTo("saved-inactive")
        assertThat(detail.revision).isNotNull()
        assertThat(storedFile("saved-inactive").isFile).isTrue()
    }

    @Test
    fun `derivation metadata is committed atomically and survives a failed write`() {
        val file = File(tempDir, "metadata.json")
        val store = FileStyleDerivationStore(file)
        assertThat(store.set("mine", "default")).isTrue()

        val failing = FileStyleDerivationStore(file, replace = { _, _ -> false })
        assertThat(failing.set("other", "default")).isFalse()

        assertThat(FileStyleDerivationStore(file).entries()).containsExactly("mine", "default")
        assertThat(file.parentFile!!.listFiles()!!.map { it.name }).containsExactly("metadata.json")
    }

    @Test
    fun `a process restart reads the last committed document and its base`(): Unit = runBlocking {
        val metadata = File(tempDir, "restart-metadata.json")
        val first = graph(derivations = FileStyleDerivationStore(metadata))
        val created = createMine(first)
        val saved = first.coordinator.update("mine", created.revision) { it.copy(name = "Latest") } as
            StyleMutationResult.Saved

        val restarted = graph(derivations = FileStyleDerivationStore(metadata))

        val committed = (restarted.repository.read("mine") as StyleReadResult.Loaded).document
        assertThat(committed).isEqualTo(saved.document)
        assertThat(restarted.derivations.baseOf("mine")).isEqualTo("default")
        assertThat(restarted.coordinator.update("mine", created.revision) { it.copy(name = "Old") })
            .isInstanceOf(StyleMutationResult.StaleStyle::class.java)
    }

    private class MapStyleAssets(private val contents: Map<String, String>) : StyleAssetSource {

        override fun names(): List<String> = contents.keys.toList()

        override fun read(name: String): String? = contents[name]
    }

    private class MutableDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {

        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    /** A pointer store that serves its initial id but cannot write. */
    private class FailingWriteDataStore(initial: Preferences = emptyPreferences()) :
        DataStore<Preferences> {

        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("pointer write failed")
    }

    /** A pointer store whose reads fail; writes are counted so tests can prove none happened. */
    private class FailingReadDataStore(initial: Preferences = emptyPreferences()) :
        DataStore<Preferences> {

        var writes = 0
            private set

        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = flow {
            throw IOException("pointer read failed")
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    /**
     * Blocks the first metadata write so a second mutation can be started while
     * the first holds the coordinator's lock.
     */
    private class GatedDerivationStore : StyleDerivationStore {

        private val recorded = linkedMapOf<String, String>()
        private var gated = false

        val firstSetStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override fun baseOf(customId: String): String? = recorded[customId]

        override fun entries(): Map<String, String> = recorded.toMap()

        override fun set(customId: String, baseId: String): Boolean {
            if (!gated) {
                gated = true
                firstSetStarted.complete(Unit)
                runBlocking { release.await() }
            }
            recorded[customId] = baseId
            return true
        }

        override fun clear(customId: String): Boolean {
            recorded.remove(customId)
            return true
        }
    }
}
