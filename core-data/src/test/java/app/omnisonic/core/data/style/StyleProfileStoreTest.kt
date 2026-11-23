package app.omnisonic.core.data.style

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.DarkMode
import app.omnisonic.core.model.style.NowPlayingBlockKind
import app.omnisonic.core.model.style.StyleApp
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleExpandedPlayer
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class StyleProfileStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val warnings = mutableListOf<String>()

    private val logger = OmnisonicLogger(
        LogSink { level, _, message, _ ->
            if (level >= LogLevel.WARN) warnings += message
        },
    )

    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private val baseDocument = StyleDocument(
        id = "default",
        name = "Default",
        app = StyleApp(
            darkMode = DarkMode.DARK,
            cornerScale = CornerRoundness.LARGE,
        ),
    )

    private fun stylesDir(): File = File(tempDir, StyleRepository.STYLES_DIRECTORY)

    private fun baseText(): String =
        (StyleWriter.write(baseDocument) as StyleWriteResult.Written).text

    private fun repository(): StyleRepository = StyleRepository(
        stylesDir = stylesDir(),
        assets = MapStyleAssets(mapOf("default.json" to baseText())),
        logger = logger,
    )

    private fun resolver(repository: StyleRepository) =
        StyleProfileResolver(repository, "default", logger)

    private fun newDataStore(file: File): DataStore<Preferences> {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = storeScope
        return PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
    }

    private fun newStore(
        file: File = File(tempDir, "style-${System.nanoTime()}.preferences_pb"),
        repository: StyleRepository = repository(),
    ): StyleProfileStore = StyleProfileStore(
        dataStore = newDataStore(file),
        repository = repository,
        resolver = resolver(repository),
        logger = logger,
    )

    private fun store(id: String, text: String): File =
        File(stylesDir(), "$id${StyleRepository.FILE_SUFFIX}").apply {
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
    fun `a fresh store resolves the default built-in quietly`() = runTest {
        val store = newStore()

        assertThat(store.activeId.first()).isNull()
        val state = resolved(store.activeProfile.first())
        assertThat(state.document).isEqualTo(baseDocument)
        assertThat(state.fellBack).isFalse()
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `the active id persists across a new store instance`() = runTest {
        val file = File(tempDir, "shared.preferences_pb")
        val repository = repository()
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = StyleProfileStore(
            dataStore = PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
            repository = repository,
            resolver = resolver(repository),
            logger = logger,
        )
        store("mine", profileText())

        assertThat(first.switch("mine")).isEqualTo(StyleSwitchResult.Switched("mine"))
        firstScope.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }
        check(firstJob.isCompleted) { "writer scope never completed" }

        val second = newStore(file, repository)

        assertThat(second.activeId.first()).isEqualTo("mine")
        val state = resolved(second.activeProfile.first())
        assertThat(state.document.id).isEqualTo("mine")
        assertThat(state.document.app.cornerScale).isEqualTo(CornerRoundness.MEDIUM)
        assertThat(state.document.app.darkMode).isEqualTo(DarkMode.DARK)
        assertThat(state.fellBack).isFalse()
    }

    @Test
    fun `a switch emits the new resolved document once`(): Unit = runBlocking {
        val store = newStore()
        store("mine", profileText())
        val emissions = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            store.activeProfile.collect { state -> emissions += resolved(state).document.id }
        }

        withTimeout(2_000) { while (emissions.isEmpty()) delay(10) }
        assertThat(store.switch("mine")).isEqualTo(StyleSwitchResult.Switched("mine"))
        withTimeout(2_000) { while (emissions.size < 2) delay(10) }
        delay(150)
        collector.cancelAndJoin()

        assertThat(emissions).containsExactly("default", "mine")
    }

    @Test
    fun `an invalid candidate is rejected and never replaces the active style`() = runTest {
        val store = newStore()
        store("mine", profileText())
        store("broken", profileText(id = "broken", app = """{"darkMode":"PURPLE"}"""))
        store.switch("mine")

        val result = store.switch("broken")

        assertThat(result).isInstanceOf(StyleSwitchResult.Rejected::class.java)
        assertThat((result as StyleSwitchResult.Rejected).firstMessage).contains("app.darkMode")
        assertThat(store.activeId.first()).isEqualTo("mine")
        assertThat(resolved(store.activeProfile.first()).document.id).isEqualTo("mine")
    }

    @Test
    fun `a missing candidate is rejected and keeps the active style`() = runTest {
        val store = newStore()
        store("mine", profileText())
        store.switch("mine")

        val result = store.switch("ghost")

        assertThat(result).isInstanceOf(StyleSwitchResult.Rejected::class.java)
        assertThat((result as StyleSwitchResult.Rejected).firstMessage).contains("does not exist")
        assertThat(store.activeId.first()).isEqualTo("mine")
    }

    @Test
    fun `deleting the active style falls back to the default built-in`() = runTest {
        val store = newStore()
        val file = store("mine", profileText())
        store.switch("mine")

        assertThat(store.delete("mine")).isEqualTo(StyleDeleteOutcome.Deleted("mine"))

        assertThat(file.exists()).isFalse()
        assertThat(store.activeId.first()).isNull()
        val state = resolved(store.activeProfile.first())
        assertThat(state.document).isEqualTo(baseDocument)
        assertThat(state.fellBack).isFalse()
    }

    @Test
    fun `deleting an inactive style keeps the active id`() = runTest {
        val store = newStore()
        store("mine", profileText())
        store("other", profileText(id = "other"))
        store.switch("mine")

        assertThat(store.delete("other")).isEqualTo(StyleDeleteOutcome.Deleted("other"))

        assertThat(store.activeId.first()).isEqualTo("mine")
        assertThat(resolved(store.activeProfile.first()).document.id).isEqualTo("mine")
    }

    @Test
    fun `a failed delete of the active style keeps the pointer`() = runTest {
        val store = newStore()
        val file = store("mine", profileText())
        store.switch("mine")
        file.delete()
        File(stylesDir(), "mine.json").apply {
            mkdirs()
            File(this, "keep").writeText("not empty")
        }

        val outcome = store.delete("mine")

        assertThat(outcome).isInstanceOf(StyleDeleteOutcome.Unwritable::class.java)
        assertThat(store.activeId.first()).isEqualTo("mine")
        assertThat(File(stylesDir(), "mine.json").isDirectory).isTrue()
    }

    @Test
    fun `the active profile observes a write to the active file`(): Unit = runBlocking {
        val repository = repository()
        val store = newStore(repository = repository)
        store("mine", profileText(app = """{"cornerScale":"MEDIUM"}"""))
        store.switch("mine")
        val states = mutableListOf<StyleProfileState.Resolved>()
        val collector = launch(Dispatchers.Default) {
            store.activeProfile.collect { states += resolved(it) }
        }
        withTimeout(2_000) { while (states.isEmpty()) delay(10) }

        repository.write(
            StyleDocument(
                id = "mine",
                name = "Renamed",
                app = StyleApp(cornerScale = CornerRoundness.EXTRA_LARGE),
            ),
        )

        withTimeout(2_000) { while (states.none { it.document.name == "Renamed" }) delay(10) }
        collector.cancelAndJoin()
        val latest = states.last()
        assertThat(latest.document.id).isEqualTo("mine")
        assertThat(latest.document.app.cornerScale).isEqualTo(CornerRoundness.EXTRA_LARGE)
        assertThat(latest.fellBack).isFalse()
    }

    @Test
    fun `a write to an inactive file does not re-emit the active profile`(): Unit = runBlocking {
        val repository = repository()
        val store = newStore(repository = repository)
        store("mine", profileText())
        store("other", profileText(id = "other"))
        store.switch("mine")
        val ids = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) {
            store.activeProfile.collect { ids += resolved(it).document.id }
        }
        withTimeout(2_000) { while (ids.isEmpty()) delay(10) }

        repository.write(StyleDocument(id = "other", name = "Changed other"))
        val grew = withTimeoutOrNull(500) { while (ids.size < 2) delay(10) }
        collector.cancelAndJoin()

        assertThat(grew).isNull()
        assertThat(ids).containsExactly("mine")
    }

    @Test
    fun `a partial orientation pair switches through the merged resolution`(): Unit = runTest {
        val repository = repository()
        val store = newStore(repository = repository)
        val partial = """
            {
              "schemaVersion": 1,
              "id": "partial",
              "name": "Partial",
              "app": {},
              "home": {},
              "pages": {},
              "nowPlaying": {"expanded": {"portrait": {"blocks": [{"kind": "QUEUE"}]}}}
            }
        """.trimIndent() + "\n"
        store("partial", partial)
        assertThat(repository.read("partial"))
            .isInstanceOf(StyleReadResult.Rejected::class.java)

        assertThat(store.switch("partial")).isEqualTo(StyleSwitchResult.Switched("partial"))

        val state = resolved(store.activeProfile.first())
        assertThat(state.document.id).isEqualTo("partial")
        assertThat(state.document.nowPlaying.expanded.portrait.blocks.map { it.kind })
            .containsExactly(NowPlayingBlockKind.QUEUE)
        assertThat(state.document.nowPlaying.expanded.landscape.blocks)
            .isEqualTo(StyleExpandedPlayer.DEFAULT_BLOCKS)
    }

    @Test
    fun `a concurrent switch and delete cannot leave a deleted style active`(): Unit = runBlocking {
        val repository = repository()
        store("a", profileText(id = "a"))
        store("b", profileText(id = "b"))
        val dataStore = GatedDataStore(
            mutablePreferencesOf(stringPreferencesKey("style.active_id") to "a"),
        )
        val store = StyleProfileStore(dataStore, repository, resolver(repository), logger)

        val switched = async(Dispatchers.Default) { store.switch("b") }
        dataStore.writeStarted.await()
        val deleted = async(Dispatchers.Default) { store.delete("b") }
        delay(100)
        dataStore.release()

        assertThat(switched.await()).isEqualTo(StyleSwitchResult.Switched("b"))
        assertThat(deleted.await()).isEqualTo(StyleDeleteOutcome.Deleted("b"))
        assertThat(store.activeId.first()).isNull()
        assertThat(File(stylesDir(), "a${StyleRepository.FILE_SUFFIX}").exists()).isTrue()
    }

    @Test
    fun `a DataStore read error resolves the default without crashing`() = runTest {
        val repository = repository()
        val store = StyleProfileStore(
            dataStore = FailingDataStore(),
            repository = repository,
            resolver = resolver(repository),
            logger = logger,
        )

        val state = resolved(withTimeout(2_000) { store.activeProfile.first() })

        assertThat(state.document).isEqualTo(baseDocument)
        assertThat(warnings).hasSize(1)
    }

    private class FailingDataStore : DataStore<Preferences> {

        override val data: Flow<Preferences> = flow { throw IOException("read failed") }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("read failed")
    }

    /**
     * A DataStore whose first write blocks until [release] so two store
     * operations can be interleaved deterministically.
     */
    private class GatedDataStore(initial: Preferences) : DataStore<Preferences> {

        private val state = MutableStateFlow(initial)
        private val started = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        val writeStarted: CompletableDeferred<Unit> get() = started

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            started.complete(Unit)
            released.await()
            val next = transform(state.value)
            state.value = next
            return next
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private class MapStyleAssets(private val contents: Map<String, String>) : StyleAssetSource {

        override fun names(): List<String> = contents.keys.toList()

        override fun read(name: String): String? = contents[name]
    }
}
