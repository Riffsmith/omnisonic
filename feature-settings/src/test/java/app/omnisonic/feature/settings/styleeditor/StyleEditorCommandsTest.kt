package app.omnisonic.feature.settings.styleeditor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.omnisonic.core.data.style.FileStyleDerivationStore
import app.omnisonic.core.data.style.StyleAssetSource
import app.omnisonic.core.data.style.StyleDerivationStore
import app.omnisonic.core.data.style.StyleIdGenerator
import app.omnisonic.core.data.style.StyleMutationCoordinator
import app.omnisonic.core.data.style.StyleMutationResult
import app.omnisonic.core.data.style.StyleProfileResolver
import app.omnisonic.core.data.style.StyleProfileStore
import app.omnisonic.core.data.style.StyleReadResult
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.data.style.styleRevisionOf
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
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The committed-edit seam (D-116 Q6/Q8, TODO 5b WP01): the first edit of a
 * built-in copies it to a fresh id with its derivation base, switches once,
 * clears session history and lands the mutation on the copy only. A user style
 * takes exactly one update and no copy. Failures keep the coordinator's typed
 * shapes and never record an undo entry.
 */
class StyleEditorCommandsTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = OmnisonicLogger(LogSink { _, _, _, _ -> })

    private val builtInDocument = StyleDocument(
        id = "default",
        name = "Default",
        app = StyleApp(
            darkMode = DarkMode.DARK,
            cornerScale = CornerRoundness.MEDIUM,
        ),
    )

    private val builtInText: String =
        (StyleWriter.write(builtInDocument) as StyleWriteResult.Written).text

    private fun stylesDir(): File = File(tempDir, StyleRepository.STYLES_DIRECTORY)

    private fun derivationFile(): File = File(tempDir, "style-derivations.json")

    private class WriteCounter {
        var count = 0
    }

    private class CountingDataStore(initial: Preferences = emptyPreferences()) :
        DataStore<Preferences> {

        var writes = 0
            private set

        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes += 1
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private class FailingWriteDataStore : DataStore<Preferences> {

        var attempts = 0
            private set

        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            attempts += 1
            throw IOException("pointer write failed")
        }
    }

    private class Graph(
        val repository: StyleRepository,
        val derivations: StyleDerivationStore,
        val store: StyleProfileStore,
        val coordinator: StyleMutationCoordinator,
        val dataStore: DataStore<Preferences>,
        val session: StyleEditorSession,
        val commands: StyleEditorCommands,
        val writes: WriteCounter,
    )

    private fun graph(
        dataStore: DataStore<Preferences> = CountingDataStore(),
        replace: (temp: File, target: File) -> Boolean = { temp, target -> temp.renameTo(target) },
        session: StyleEditorSession = StyleEditorSession(),
    ): Graph {
        val writes = WriteCounter()
        val repository = StyleRepository(
            stylesDir = stylesDir(),
            assets = MapStyleAssets(mapOf("default.json" to builtInText)),
            logger = logger,
            replace = { temp, target ->
                writes.count += 1
                replace(temp, target)
            },
        )
        val derivations = FileStyleDerivationStore(derivationFile())
        val store = StyleProfileStore(
            dataStore = dataStore,
            repository = repository,
            resolver = StyleProfileResolver(repository, "default", logger),
            logger = logger,
        )
        val coordinator = StyleMutationCoordinator(
            repository = repository,
            profileStore = store,
            derivations = derivations,
            logger = logger,
            defaultStyleId = "default",
            clock = { 1_700_000_000_000L },
        )
        return Graph(
            repository = repository,
            derivations = derivations,
            store = store,
            coordinator = coordinator,
            dataStore = dataStore,
            session = session,
            commands = StyleEditorCommands(repository, coordinator, session, clock = { 1_700_000_000_000L }),
            writes = writes,
        )
    }

    private fun StyleEditorCommandsTest.Graph.builtInTarget(): StyleEditTarget {
        val document = (repository.read("default") as StyleReadResult.Loaded).document
        return StyleEditTarget("default", styleRevisionOf(document)!!)
    }

    private fun StyleEditorCommandsTest.Graph.storedNames(): List<String> =
        stylesDir().listFiles()?.map { it.name }?.sorted().orEmpty()

    private fun StyleEditorCommandsTest.Graph.copyFile(id: String): File =
        File(stylesDir(), "$id${StyleRepository.FILE_SUFFIX}")

    private val recolour: (StyleDocument) -> StyleDocument = { document ->
        document.copy(app = document.app.copy(cornerScale = CornerRoundness.EXTRA_LARGE))
    }

    @Test
    fun `the first edit of a built-in copies it, records the base and switches once`(): Unit =
        runBlocking {
            val graph = graph()

            val result = graph.commands.commitEdit(graph.builtInTarget(), recolour)

            val saved = result as StyleMutationResult.Saved
            assertThat(saved.id).isNotEqualTo("default")
            assertThat(saved.document.id).isEqualTo(saved.id)
            assertThat(saved.document.name).isEqualTo("Default copy")
            assertThat(saved.document.app.cornerScale).isEqualTo(CornerRoundness.EXTRA_LARGE)
            assertThat(saved.derivationBaseId).isEqualTo("default")
            assertThat(graph.derivations.baseOf(saved.id)).isEqualTo("default")
            assertThat(graph.store.activeId.first()).isEqualTo(saved.id)
            assertThat((graph.dataStore as CountingDataStore).writes).isEqualTo(1)
            assertThat(graph.session.undoCount.value).isEqualTo(1)
            assertThat(graph.copyFile(saved.id).isFile).isTrue()
            assertThat(graph.storedNames()).containsExactly("${saved.id}.json")
        }

    @Test
    fun `the mutation lands only on the copy and the built-in stays unchanged`(): Unit =
        runBlocking {
            val graph = graph()

            val saved = graph.commands.commitEdit(graph.builtInTarget(), recolour) as
                StyleMutationResult.Saved

            val builtIn = (graph.repository.read("default") as StyleReadResult.Loaded)
            assertThat(builtIn.origin).isEqualTo(
                app.omnisonic.core.data.style.StyleOrigin.BUILT_IN,
            )
            assertThat(builtIn.document).isEqualTo(builtInDocument)
            assertThat(graph.copyFile("default").exists()).isFalse()
            assertThat(builtInText).isEqualTo(
                (StyleWriter.write(builtInDocument) as StyleWriteResult.Written).text,
            )
            val storedCopy = (graph.repository.read(saved.id) as StyleReadResult.Loaded).document
            assertThat(storedCopy.app.cornerScale).isEqualTo(CornerRoundness.EXTRA_LARGE)
            assertThat(storedCopy.app.darkMode).isEqualTo(DarkMode.DARK)
        }

    @Test
    fun `history is cleared before the mutation and holds exactly one entry`(): Unit =
        runBlocking {
            val graph = graph()
            graph.session.record()
            graph.session.record()
            var historyDuringMutation = -1

            val saved = graph.commands.commitEdit(graph.builtInTarget()) { document ->
                historyDuringMutation = graph.session.undoCount.value
                recolour(document)
            }

            assertThat(saved).isInstanceOf(StyleMutationResult.Saved::class.java)
            assertThat(historyDuringMutation).isEqualTo(0)
            assertThat(graph.session.undoCount.value).isEqualTo(1)
        }

    @Test
    fun `a copy write failure is typed, records no undo entry and writes no built-in`(): Unit =
        runBlocking {
            val graph = graph(replace = { _, _ -> false })

            val result = graph.commands.commitEdit(graph.builtInTarget(), recolour)

            assertThat(result).isInstanceOf(StyleMutationResult.Failed::class.java)
            assertThat(graph.session.undoCount.value).isEqualTo(0)
            assertThat(graph.storedNames()).isEmpty()
            assertThat((graph.dataStore as CountingDataStore).writes).isEqualTo(0)
            assertThat(graph.derivations.entries()).isEmpty()
            assertThat((graph.repository.read("default") as StyleReadResult.Loaded).document)
                .isEqualTo(builtInDocument)
        }

    @Test
    fun `a switch failure is saved inactive, typed, and the mutation never lands`(): Unit =
        runBlocking {
            val dataStore = FailingWriteDataStore()
            val graph = graph(dataStore = dataStore)

            val result = graph.commands.commitEdit(graph.builtInTarget(), recolour)

            val partial = result as StyleMutationResult.Partial
            assertThat(partial.committed?.id).isEqualTo(partial.id)
            assertThat(partial.revision).isNotNull()
            assertThat(partial.detail).contains("pointer")
            assertThat(dataStore.attempts).isEqualTo(1)
            assertThat(graph.session.undoCount.value).isEqualTo(0)
            assertThat(graph.storedNames()).containsExactly("${partial.id}.json")
            val copy = (graph.repository.read(partial.id) as StyleReadResult.Loaded).document
            assertThat(copy.app.cornerScale).isEqualTo(CornerRoundness.MEDIUM)
            assertThat(copy.name).isEqualTo("Default copy")
            assertThat(graph.derivations.baseOf(partial.id)).isEqualTo("default")
            assertThat(graph.copyFile("default").exists()).isFalse()
        }

    @Test
    fun `editing an existing user style performs one update and no copy`(): Unit = runBlocking {
        val graph = graph()
        val created = graph.coordinator.create(
            StyleDocument(id = "mine", name = "Mine"),
            "default",
        ) as StyleMutationResult.Saved
        graph.writes.count = 0

        val result = graph.commands.commitEdit(
            StyleEditTarget("mine", created.revision),
            recolour,
        )

        val saved = result as StyleMutationResult.Saved
        assertThat(saved.id).isEqualTo("mine")
        assertThat(saved.derivationBaseId).isEqualTo("default")
        assertThat(graph.writes.count).isEqualTo(1)
        assertThat(graph.storedNames()).containsExactly("mine.json")
        assertThat(graph.session.undoCount.value).isEqualTo(1)
        assertThat(graph.copyFile("default").exists()).isFalse()
        assertThat((graph.repository.read("mine") as StyleReadResult.Loaded).document.app.cornerScale)
            .isEqualTo(CornerRoundness.EXTRA_LARGE)
    }

    @Test
    fun `the generated copy name is distinguishable and unique against the list`(): Unit =
        runBlocking {
            val naming = StyleCopyNaming()
            assertThat(naming.nameFor("Default", emptyList())).isEqualTo("Default copy")
            assertThat(naming.nameFor("Default", listOf("Default copy"))).isEqualTo("Default copy 2")
            assertThat(
                naming.nameFor("Default", listOf("default copy", "Default copy 2")),
            ).isEqualTo("Default copy 3")
            assertThat(naming.nameFor("   ", emptyList())).isEqualTo("Style copy")

            val graph = graph()
            val taken = graph.coordinator.create(
                StyleDocument(id = "taken", name = "Default copy"),
                "default",
            ) as StyleMutationResult.Saved
            assertThat(taken.id).isEqualTo("taken")

            val saved = graph.commands.commitEdit(graph.builtInTarget(), recolour) as
                StyleMutationResult.Saved

            assertThat(saved.document.name).isEqualTo("Default copy 2")
            val names = graph.repository.list().map { it.name }
            assertThat(names).containsNoDuplicates()
        }

    @Test
    fun `the derivation base and document survive a simulated restart`(): Unit = runBlocking {
        val first = graph()
        val saved = first.commands.commitEdit(first.builtInTarget(), recolour) as
            StyleMutationResult.Saved

        val restarted = graph()

        assertThat(restarted.derivations.baseOf(saved.id)).isEqualTo("default")
        val committed = (restarted.repository.read(saved.id) as StyleReadResult.Loaded).document
        assertThat(committed).isEqualTo(saved.document)
        assertThat(styleRevisionOf(committed)).isEqualTo(saved.revision)
    }

    @Test
    fun `the auto-copy path leaves no temp files behind`(): Unit = runBlocking {
        val graph = graph()

        graph.commands.commitEdit(graph.builtInTarget(), recolour)

        val stylesLeftovers = graph.storedNames().filter { it.endsWith(".tmp") }
        val metadataLeftovers = tempDir.listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith(".tmp") }
            .orEmpty()
        assertThat(stylesLeftovers).isEmpty()
        assertThat(metadataLeftovers).isEmpty()
    }

    @Test
    fun `duplicating a built-in writes one fresh copy with its resolved document and base`(): Unit =
        runBlocking {
            val graph = graph()

            val result = graph.commands.duplicate("default")

            val saved = result as StyleMutationResult.Saved
            assertThat(saved.id).isNotEqualTo("default")
            assertThat(saved.document.name).isEqualTo("Default copy")
            assertThat(saved.derivationBaseId).isEqualTo("default")
            assertThat(saved.activated).isFalse()
            assertThat(graph.derivations.baseOf(saved.id)).isEqualTo("default")
            assertThat(graph.writes.count).isEqualTo(1)
            assertThat(graph.store.activeId.first()).isNull()
            assertThat((graph.dataStore as CountingDataStore).writes).isEqualTo(0)
            val stored = (graph.repository.read(saved.id) as StyleReadResult.Loaded).document
            assertThat(stored).isEqualTo(builtInDocument.copy(id = saved.id, name = "Default copy"))
            assertThat(graph.copyFile("default").exists()).isFalse()
        }

    @Test
    fun `duplicating a user style keeps its recorded base and a unique distinguishable name`():
        Unit = runBlocking {
            val graph = graph()
            val mine = StyleDocument(
                id = "mine",
                name = "Mine",
                app = StyleApp(cornerScale = CornerRoundness.LARGE, darkMode = DarkMode.LIGHT),
            )
            assertThat(graph.repository.write(mine)).isInstanceOf(
                app.omnisonic.core.data.style.StyleWriteOutcome.Written::class.java,
            )
            assertThat(graph.derivations.set("mine", "default")).isTrue()
            val taken = graph.coordinator.create(
                StyleDocument(id = "taken", name = "Mine copy"),
                "default",
            ) as StyleMutationResult.Saved
            assertThat(taken.id).isEqualTo("taken")

            val result = graph.commands.duplicate("mine")

            val saved = result as StyleMutationResult.Saved
            assertThat(saved.document.name).isEqualTo("Mine copy 2")
            assertThat(saved.derivationBaseId).isEqualTo("default")
            assertThat(graph.derivations.baseOf(saved.id)).isEqualTo("default")
            assertThat(saved.document.app.cornerScale).isEqualTo(CornerRoundness.LARGE)
            assertThat(saved.document.app.darkMode).isEqualTo(DarkMode.LIGHT)
            assertThat(graph.repository.list().map { it.name }).containsNoDuplicates()
            val stored = (graph.repository.read(saved.id) as StyleReadResult.Loaded).document
            assertThat(stored).isEqualTo(mine.copy(id = saved.id, name = "Mine copy 2"))
        }

    @Test
    fun `a duplicate write failure is typed and adds no style`(): Unit = runBlocking {
        val graph = graph(replace = { _, _ -> false })

        val result = graph.commands.duplicate("default")

        assertThat(result).isInstanceOf(StyleMutationResult.Failed::class.java)
        assertThat(graph.storedNames()).isEmpty()
        assertThat(graph.derivations.entries()).isEmpty()
        assertThat(graph.store.activeId.first()).isNull()
        assertThat((graph.repository.read("default") as StyleReadResult.Loaded).document)
            .isEqualTo(builtInDocument)
    }

    @Test
    fun `renaming a user style commits one write with the new unique name`(): Unit = runBlocking {
        val graph = graph()
        val created = graph.coordinator.create(
            StyleDocument(id = "mine", name = "Mine"),
            "default",
        ) as StyleMutationResult.Saved
        graph.writes.count = 0

        val result = graph.commands.rename(StyleEditTarget("mine", created.revision), "Mine renamed")

        val saved = result as StyleMutationResult.Saved
        assertThat(saved.id).isEqualTo("mine")
        assertThat(saved.document.name).isEqualTo("Mine renamed")
        assertThat(graph.writes.count).isEqualTo(1)
        assertThat((graph.repository.read("mine") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Mine renamed")
        assertThat(graph.derivations.baseOf("mine")).isEqualTo("default")
        assertThat(graph.copyFile("default").exists()).isFalse()
    }

    @Test
    fun `a taken or blank rename is refused with a typed failure and no write`(): Unit = runBlocking {
        val graph = graph()
        graph.coordinator.create(StyleDocument(id = "mine", name = "Mine"), "default")
        val other = graph.coordinator.create(
            StyleDocument(id = "other", name = "Other"),
            "default",
        ) as StyleMutationResult.Saved
        val target = StyleEditTarget("mine", other.revision)
        graph.writes.count = 0

        val taken = graph.commands.rename(target, "oThEr")

        assertThat(taken).isInstanceOf(StyleMutationResult.Rejected::class.java)
        val rejected = taken as StyleMutationResult.Rejected
        assertThat(rejected.errors.single().path).isEqualTo("name")
        assertThat(rejected.firstMessage).contains("oThEr")

        val blank = graph.commands.rename(target, "   ")

        assertThat(blank).isInstanceOf(StyleMutationResult.Rejected::class.java)
        assertThat(graph.writes.count).isEqualTo(0)
        assertThat((graph.repository.read("mine") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Mine")
        assertThat((graph.repository.read("other") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Other")
    }

    @Test
    fun `renaming a built-in is refused at the command layer and the asset is unchanged`(): Unit =
        runBlocking {
            val graph = graph()
            val target = graph.builtInTarget()

            val result = graph.commands.rename(target, "Renamed")

            assertThat(result).isInstanceOf(StyleMutationResult.Rejected::class.java)
            val rejected = result as StyleMutationResult.Rejected
            assertThat(rejected.id).isEqualTo("default")
            assertThat(rejected.firstMessage).contains("built-in")
            assertThat(graph.writes.count).isEqualTo(0)
            assertThat(graph.storedNames()).isEmpty()
            val loaded = graph.repository.read("default") as StyleReadResult.Loaded
            assertThat(loaded.origin).isEqualTo(
                app.omnisonic.core.data.style.StyleOrigin.BUILT_IN,
            )
            assertThat(loaded.document).isEqualTo(builtInDocument)
            assertThat(builtInText).isEqualTo(
                (StyleWriter.write(builtInDocument) as StyleWriteResult.Written).text,
            )
        }

    @Test
    fun `delete removes the style and its metadata without pushing an undo entry`(): Unit =
        runBlocking {
            val graph = graph()
            graph.coordinator.create(StyleDocument(id = "mine", name = "Mine"), "default")
            graph.session.record()
            val before = graph.session.undoCount.value

            val result = graph.commands.delete("mine")

            assertThat(result).isEqualTo(StyleMutationResult.Deleted("mine"))
            assertThat(graph.session.undoCount.value).isEqualTo(before)
            assertThat(graph.storedNames()).isEmpty()
            assertThat(graph.derivations.baseOf("mine")).isNull()
        }

    private class MapStyleAssets(private val contents: Map<String, String>) : StyleAssetSource {

        override fun names(): List<String> = contents.keys.toList()

        override fun read(name: String): String? = contents[name]
    }
}
