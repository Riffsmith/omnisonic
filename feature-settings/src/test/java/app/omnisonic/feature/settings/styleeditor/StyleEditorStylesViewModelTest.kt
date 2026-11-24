package app.omnisonic.feature.settings.styleeditor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.omnisonic.core.data.style.FileStyleDerivationStore
import app.omnisonic.core.data.style.StyleAssetSource
import app.omnisonic.core.data.style.StyleDerivationStore
import app.omnisonic.core.data.style.StyleMutationCoordinator
import app.omnisonic.core.data.style.StyleOrigin
import app.omnisonic.core.data.style.StyleProfileResolver
import app.omnisonic.core.data.style.StyleProfileState
import app.omnisonic.core.data.style.StyleProfileStore
import app.omnisonic.core.data.style.StyleReadResult
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.data.style.StyleWriteOutcome
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.StyleApp
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import app.omnisonic.style.ProfileStyleTokenSource
import app.omnisonic.style.StyleTokenResolver
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The Styles sub-screen state (D-116 Q9, TODO 5b WP02): every valid style once
 * by name in repository order, the applied style as the only marked entry, one
 * preview token set for that active style, and a pick that applies live through
 * the 5a0 coordinator. A refusal or pointer failure stays typed and never looks
 * applied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StyleEditorStylesViewModelTest {

    @TempDir
    lateinit var tempDir: File

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val logger = OmnisonicLogger(LogSink { _, _, _, _ -> })

    private val alphaDocument = StyleDocument(
        id = "alpha",
        name = "Alpha",
        app = StyleApp(cornerScale = CornerRoundness.DEFAULT),
    )

    private val betaDocument = StyleDocument(
        id = "beta",
        name = "Beta",
        app = StyleApp(cornerScale = CornerRoundness.LARGE),
    )

    private val midDocument = StyleDocument(
        id = "mid",
        name = "Mid",
        app = StyleApp(cornerScale = CornerRoundness.EXTRA_LARGE),
    )

    private val zetaDocument = StyleDocument(
        id = "zeta",
        name = "Zeta",
        app = StyleApp(cornerScale = CornerRoundness.MEDIUM),
    )

    private class MapStyleAssets(private val contents: Map<String, String>) : StyleAssetSource {

        override fun names(): List<String> = contents.keys.toList()

        override fun read(name: String): String? = contents[name]
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
        val viewModel: StyleEditorViewModel,
        val store: StyleProfileStore,
        val repository: StyleRepository,
        val derivations: StyleDerivationStore,
        val dataStore: DataStore<Preferences>,
        val writes: WriteCounter,
    )

    private class WriteCounter {
        var count = 0
    }

    private fun text(document: StyleDocument): String =
        (StyleWriter.write(document) as StyleWriteResult.Written).text

    private fun stylesDir(): File = File(tempDir, StyleRepository.STYLES_DIRECTORY)

    private fun damagedFile(id: String): File =
        File(stylesDir(), "$id${StyleRepository.FILE_SUFFIX}").apply {
            parentFile?.mkdirs()
            writeText("{ not a style\n")
        }

    private fun graph(
        dataStore: DataStore<Preferences> = CountingDataStore(),
        failWrites: () -> Boolean = { false },
    ): Graph {
        val writes = WriteCounter()
        val repository = StyleRepository(
            stylesDir = stylesDir(),
            assets = MapStyleAssets(
                mapOf(
                    "alpha.json" to text(alphaDocument),
                    "beta.json" to text(betaDocument),
                ),
            ),
            logger = logger,
            replace = { temp, target ->
                writes.count += 1
                if (failWrites()) false else temp.renameTo(target)
            },
        )
        repository.write(midDocument)
        repository.write(zetaDocument)
        writes.count = 0
        val derivations = FileStyleDerivationStore(File(tempDir, "style-derivations.json"))
        val store = StyleProfileStore(
            dataStore = dataStore,
            repository = repository,
            resolver = StyleProfileResolver(repository, "alpha", logger),
            logger = logger,
        )
        val coordinator = StyleMutationCoordinator(
            repository = repository,
            profileStore = store,
            derivations = derivations,
            logger = logger,
            defaultStyleId = "alpha",
        )
        val tokenSource = ProfileStyleTokenSource(
            store.activeProfile.map { state -> (state as? StyleProfileState.Resolved)?.document },
        )
        return Graph(
            viewModel = StyleEditorViewModel(store, tokenSource, repository, coordinator),
            store = store,
            repository = repository,
            derivations = derivations,
            dataStore = dataStore,
            writes = writes,
        )
    }

    private suspend fun loaded(
        graph: Graph,
        size: Int = 4,
    ): StyleEditorStylesState =
        withTimeout(5_000) { graph.viewModel.stylesState.first { it.entries.size == size } }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `styles are listed by name in repository order with the active entry marked once`(): Unit =
        runTest {
            val graph = graph()

            val state = loaded(graph)

            assertThat(state.entries.map { it.name })
                .containsExactly("Alpha", "Beta", "Mid", "Zeta")
                .inOrder()
            assertThat(state.entries.map { it.id })
                .containsExactly("alpha", "beta", "mid", "zeta")
                .inOrder()
            assertThat(state.activeId).isEqualTo("alpha")
            assertThat(state.entries.count { it.id == state.activeId }).isEqualTo(1)
            assertThat(state.pendingStyleId).isNull()
            assertThat(state.failure).isNull()
        }

    @Test
    fun `picking a style switches it live so the marker and the preview follow`(): Unit = runTest {
        val graph = graph()
        val initial = loaded(graph)
        assertThat(initial.previewTokens).isEqualTo(StyleTokenResolver.resolve(alphaDocument))

        graph.viewModel.pick("mid")

        val picked = withTimeout(5_000) {
            graph.viewModel.stylesState.first { it.activeId == "mid" && it.pendingStyleId == null }
        }
        assertThat(picked.failure).isNull()
        assertThat(picked.activeStyleName).isEqualTo("Mid")
        assertThat(picked.entries.count { it.id == "mid" }).isEqualTo(1)
        assertThat(picked.previewTokens).isEqualTo(StyleTokenResolver.resolve(midDocument))
        assertThat(picked.previewTokens).isNotEqualTo(initial.previewTokens)
        assertThat(withTimeout(5_000) { graph.store.activeId.first() }).isEqualTo("mid")
    }

    @Test
    fun `a rejected switch is typed and leaves the active entry and preview unchanged`(): Unit =
        runTest {
            val graph = graph()
            val initial = loaded(graph)

            graph.viewModel.pick("missing")

            val rejected = withTimeout(5_000) {
                graph.viewModel.stylesState.first { it.failure != null && it.pendingStyleId == null }
            }
            val failure = rejected.failure as StyleEditorSwitchFailure.Rejected
            assertThat(failure.styleId).isEqualTo("missing")
            assertThat(failure.message).isNotEmpty()
            assertThat(rejected.activeId).isEqualTo(initial.activeId)
            assertThat(rejected.activeStyleName).isEqualTo("Alpha")
            assertThat(rejected.previewTokens).isEqualTo(initial.previewTokens)
            assertThat(withTimeout(5_000) { graph.store.activeId.first() }).isNull()
        }

    @Test
    fun `a pointer write failure is typed and nothing looks applied`(): Unit = runTest {
        val graph = graph(dataStore = FailingWriteDataStore())
        val initial = loaded(graph)

        graph.viewModel.pick("mid")

        val failed = withTimeout(5_000) {
            graph.viewModel.stylesState.first { it.failure != null && it.pendingStyleId == null }
        }
        val failure = failed.failure as StyleEditorSwitchFailure.Failed
        assertThat(failure.styleId).isEqualTo("mid")
        assertThat(failure.message).contains("pointer")
        assertThat((graph.dataStore as FailingWriteDataStore).attempts).isEqualTo(1)
        assertThat(failed.activeId).isEqualTo(initial.activeId)
        assertThat(failed.previewTokens).isEqualTo(initial.previewTokens)
    }

    @Test
    fun `entries carry only identity and the state holds one preview for the active style`(): Unit =
        runTest {
            val graph = graph()
            val initial = loaded(graph)
            assertThat(initial.entries.map { Triple(it.id, it.name, it.origin) })
                .containsExactly(
                    Triple("alpha", "Alpha", StyleOrigin.BUILT_IN),
                    Triple("beta", "Beta", StyleOrigin.BUILT_IN),
                    Triple("mid", "Mid", StyleOrigin.USER),
                    Triple("zeta", "Zeta", StyleOrigin.USER),
                ).inOrder()
            assertThat(initial.entries.all { it.kind == StyleEditorEntryKind.VALID }).isTrue()
            assertThat(initial.entries.all { it.damageDetail == null }).isTrue()
            assertThat(initial.entries.filter { it.origin == StyleOrigin.BUILT_IN }.all {
                it.modifiedAt == null
            }).isTrue()

            graph.viewModel.pick("beta")

            val picked = withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.activeId == "beta" && it.pendingStyleId == null
                }
            }
            assertThat(picked.entries).isEqualTo(initial.entries)
            assertThat(picked.previewTokens).isEqualTo(StyleTokenResolver.resolve(betaDocument))
            assertThat(picked.previewTokens).isNotEqualTo(initial.previewTokens)
        }

    @Test
    fun `picking writes no style document and leaves no temp file`(): Unit = runTest {
        val graph = graph()
        loaded(graph)
        val before = stylesDir().listFiles()?.sortedBy { it.name }?.map { it.name to it.readText() }
            .orEmpty()
        assertThat(before.map { it.first }).containsExactly("mid.json", "zeta.json").inOrder()

        graph.viewModel.pick("zeta")
        withTimeout(5_000) {
            graph.viewModel.stylesState.first { it.activeId == "zeta" && it.pendingStyleId == null }
        }

        val after = stylesDir().listFiles()?.sortedBy { it.name }?.map { it.name to it.readText() }
            .orEmpty()
        assertThat(after).isEqualTo(before)
        assertThat(after.none { it.first.endsWith(StyleRepository.TEMP_SUFFIX) }).isTrue()
        assertThat((graph.dataStore as CountingDataStore).writes).isEqualTo(1)
    }

    @Test
    fun `duplicating a built-in creates a fresh resolved copy and does not switch`(): Unit = runTest {
        val graph = graph()
        val initial = loaded(graph)

        graph.viewModel.duplicate("beta")

        val copied = withTimeout(5_000) {
            graph.viewModel.stylesState.first {
                it.entries.size == 5 && it.pendingCommandId == null
            }
        }
        val copy = copied.entries.single { it.name == "Beta copy" }
        assertThat(copy.origin).isEqualTo(StyleOrigin.USER)
        assertThat(copy.id).isNotEqualTo("beta")
        val stored = (graph.repository.read(copy.id) as StyleReadResult.Loaded).document
        assertThat(stored).isEqualTo(betaDocument.copy(id = copy.id, name = "Beta copy"))
        assertThat(graph.derivations.baseOf(copy.id)).isEqualTo("beta")
        assertThat(graph.writes.count).isEqualTo(1)
        assertThat(copied.activeId).isEqualTo("alpha")
        assertThat(copied.activeStyleName).isEqualTo("Alpha")
        assertThat(copied.previewTokens).isEqualTo(initial.previewTokens)
        assertThat(withTimeout(5_000) { graph.store.activeId.first() }).isNull()
        assertThat(copied.commandFailure).isNull()
        assertThat(copied.renameTargetId).isNull()
    }

    @Test
    fun `duplicating a user style keeps its base and picks a unique distinguishable name`(): Unit =
        runTest {
            val graph = graph()
            val mine = StyleDocument(
                id = "mine",
                name = "Mine",
                app = StyleApp(cornerScale = CornerRoundness.LARGE),
            )
            assertThat(graph.repository.write(mine)).isInstanceOf(StyleWriteOutcome.Written::class.java)
            assertThat(graph.derivations.set("mine", "beta")).isTrue()
            assertThat(
                graph.repository.write(StyleDocument(id = "minecopy", name = "Mine copy")),
            ).isInstanceOf(StyleWriteOutcome.Written::class.java)
            val initial = loaded(graph, size = 6)
            graph.writes.count = 0

            graph.viewModel.duplicate("mine")

            val copied = withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.entries.size == 7 && it.pendingCommandId == null
                }
            }
            val copy = copied.entries.first { it.name == "Mine copy 2" }
            assertThat(copy.origin).isEqualTo(StyleOrigin.USER)
            assertThat(graph.derivations.baseOf(copy.id)).isEqualTo("beta")
            val stored = (graph.repository.read(copy.id) as StyleReadResult.Loaded).document
            assertThat(stored).isEqualTo(mine.copy(id = copy.id, name = "Mine copy 2"))
            assertThat(copied.entries.map { it.name }).containsNoDuplicates()
            assertThat(graph.writes.count).isEqualTo(1)
            assertThat(copied.activeId).isEqualTo(initial.activeId)
            assertThat(copied.previewTokens).isEqualTo(initial.previewTokens)
        }

    @Test
    fun `a duplicate write failure is typed, adds no style and leaves the preview untouched`(): Unit =
        runTest {
            var failing = false
            val graph = graph(failWrites = { failing })
            val initial = loaded(graph)
            failing = true

            graph.viewModel.duplicate("beta")

            val failed = withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.commandFailure != null && it.pendingCommandId == null
                }
            }
            val failure = failed.commandFailure ?: error("expected a typed duplicate failure")
            assertThat(failure.kind).isEqualTo(StyleEditorCommandKind.DUPLICATE)
            assertThat(failure.styleId).isEqualTo("beta")
            assertThat(failure.message).isNotEmpty()
            assertThat(failed.entries).isEqualTo(initial.entries)
            assertThat(failed.activeId).isEqualTo(initial.activeId)
            assertThat(failed.activeStyleName).isEqualTo("Alpha")
            assertThat(failed.previewTokens).isEqualTo(initial.previewTokens)
            assertThat(stylesDir().listFiles()?.map { it.name }?.sorted().orEmpty())
                .containsExactly("mid.json", "zeta.json")
        }

    @Test
    fun `renaming a user style writes once and the marker and preview follow it`(): Unit = runTest {
        val graph = graph()
        loaded(graph)
        graph.viewModel.pick("mid")
        val before = withTimeout(5_000) {
            graph.viewModel.stylesState.first {
                it.activeId == "mid" && it.pendingStyleId == null
            }
        }
        graph.writes.count = 0

        graph.viewModel.beginRename("mid")
        val opened = withTimeout(5_000) {
            graph.viewModel.stylesState.first { it.renameTargetId == "mid" }
        }
        assertThat(opened.renameDraft).isEqualTo("Mid")
        graph.viewModel.updateRenameDraft("Middle")
        graph.viewModel.confirmRename()

        val renamed = withTimeout(5_000) {
            graph.viewModel.stylesState.first {
                it.entries.any { entry -> entry.name == "Middle" } && it.pendingCommandId == null
            }
        }
        assertThat(renamed.activeId).isEqualTo("mid")
        assertThat(renamed.activeStyleName).isEqualTo("Middle")
        assertThat(renamed.previewTokens).isEqualTo(before.previewTokens)
        assertThat(renamed.renameTargetId).isNull()
        assertThat(renamed.commandFailure).isNull()
        assertThat(graph.writes.count).isEqualTo(1)
        val stored = (graph.repository.read("mid") as StyleReadResult.Loaded).document
        assertThat(stored.name).isEqualTo("Middle")
    }

    @Test
    fun `a taken or blank rename is refused with a typed failure and no write`(): Unit = runTest {
        val graph = graph()
        loaded(graph)
        graph.writes.count = 0

        graph.viewModel.beginRename("mid")
        graph.viewModel.updateRenameDraft("zEtA")
        graph.viewModel.confirmRename()

        var failed = withTimeout(5_000) {
            graph.viewModel.stylesState.first {
                it.commandFailure != null && it.pendingCommandId == null
            }
        }
        var failure = failed.commandFailure
        assertThat(failure?.kind).isEqualTo(StyleEditorCommandKind.RENAME)
        assertThat(failure?.styleId).isEqualTo("mid")
        assertThat(failure?.message).contains("zEtA")
        assertThat(failed.renameTargetId).isEqualTo("mid")
        assertThat(failed.entries.first { it.id == "mid" }.name).isEqualTo("Mid")

        graph.viewModel.updateRenameDraft("   ")
        graph.viewModel.confirmRename()

        failed = withTimeout(5_000) {
            graph.viewModel.stylesState.first {
                it.commandFailure?.message?.contains("name") == true
            }
        }
        failure = failed.commandFailure
        assertThat(failure?.kind).isEqualTo(StyleEditorCommandKind.RENAME)
        assertThat(failed.renameTargetId).isEqualTo("mid")
        assertThat(failed.entries.first { it.id == "mid" }.name).isEqualTo("Mid")
        assertThat(graph.writes.count).isEqualTo(0)
        assertThat((graph.repository.read("mid") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Mid")
        assertThat((graph.repository.read("zeta") as StyleReadResult.Loaded).document.name)
            .isEqualTo("Zeta")
    }

    @Test
    fun `deleting a user style removes only it and writes no document`(): Unit = runTest {
        val graph = graph()
        val initial = loaded(graph)
        assertThat(stylesDir().listFiles()?.map { it.name }).containsExactly("mid.json", "zeta.json")

        graph.viewModel.beginDelete("alpha")
        assertThat(graph.viewModel.stylesState.value.confirmTarget).isNull()

        graph.viewModel.beginDelete("zeta")
        val confirming = withTimeout(5_000) {
            graph.viewModel.stylesState.first { it.confirmTarget != null }
        }
        assertThat(confirming.confirmTarget?.styleId).isEqualTo("zeta")
        assertThat(confirming.confirmTarget?.styleName).isEqualTo("Zeta")
        assertThat(confirming.confirmTarget?.action).isEqualTo(StyleEditorConfirmAction.DELETE)

        graph.viewModel.confirm()

        val deleted = withTimeout(5_000) {
            graph.viewModel.stylesState.first {
                it.entries.none { entry -> entry.id == "zeta" } && it.confirmTarget == null
            }
        }
        assertThat(deleted.commandFailure).isNull()
        assertThat(deleted.entries.map { it.id }).containsExactly("alpha", "beta", "mid").inOrder()
        assertThat(stylesDir().listFiles()?.map { it.name }).containsExactly("mid.json")
        assertThat((graph.dataStore as CountingDataStore).writes).isEqualTo(0)
        assertThat(graph.writes.count).isEqualTo(0)
        assertThat(deleted.activeId).isEqualTo(initial.activeId)
        assertThat(deleted.previewTokens).isEqualTo(initial.previewTokens)
        assertThat(deleted.fallbackReason).isNull()
    }

    @Test
    fun `deleting the active style falls back to the default and says the selection was deleted`() =
        runTest {
            val graph = graph()
            loaded(graph)
            graph.viewModel.pick("zeta")
            withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.activeId == "zeta" && it.pendingStyleId == null
                }
            }
            graph.viewModel.beginDelete("zeta")
            withTimeout(5_000) { graph.viewModel.stylesState.first { it.confirmTarget != null } }

            graph.viewModel.confirm()

            val deleted = withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.activeId == "alpha" && it.fallbackReason != null
                }
            }
            assertThat(deleted.fallbackReason)
                .isEqualTo(StyleEditorFallbackReason.DELETED_SELECTION)
            assertThat(deleted.activeStyleName).isEqualTo("Alpha")
            assertThat(deleted.previewTokens).isEqualTo(StyleTokenResolver.resolve(alphaDocument))
            assertThat(deleted.entries.count { it.id == deleted.activeId }).isEqualTo(1)
            assertThat(deleted.entries.none { it.id == "zeta" }).isTrue()
            assertThat(withTimeout(5_000) { graph.store.activeId.first() }).isNull()

            graph.viewModel.pick("mid")

            val repicked = withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.activeId == "mid" && it.pendingStyleId == null
                }
            }
            assertThat(repicked.fallbackReason).isNull()
        }

    @Test
    fun `a damaged user file is listed for attention and never becomes the active style`() = runTest {
        val broken = damagedFile("broken")
        val graph = graph()

        val state = withTimeout(5_000) {
            graph.viewModel.stylesState.first { listing ->
                listing.entries.any { it.kind == StyleEditorEntryKind.DAMAGED }
            }
        }
        val damaged = state.entries.single { it.kind == StyleEditorEntryKind.DAMAGED }
        assertThat(damaged.id).isEqualTo("broken")
        assertThat(damaged.name).isEqualTo("broken")
        assertThat(damaged.origin).isEqualTo(StyleOrigin.USER)
        assertThat(damaged.damagePath).isEqualTo(broken.absolutePath)
        assertThat(damaged.damageDetail).contains(broken.absolutePath)
        assertThat(damaged.modifiedAt).isNotNull()
        assertThat(state.entries.filter { it.kind == StyleEditorEntryKind.VALID }.map { it.id })
            .containsExactly("alpha", "beta", "mid", "zeta")
            .inOrder()

        graph.viewModel.pick("broken")

        assertThat(graph.viewModel.stylesState.value.pendingStyleId).isNull()
        assertThat(graph.viewModel.stylesState.value.failure).isNull()
        assertThat(withTimeout(5_000) { graph.store.activeId.first() }).isNull()
    }

    @Test
    fun `an active damaged selection explains the default fallback and stays unpickable`() = runTest {
        damagedFile("broken")
        val graph = graph(
            dataStore = CountingDataStore(
                mutablePreferencesOf(stringPreferencesKey("style.active_id") to "broken"),
            ),
        )

        val state = withTimeout(5_000) {
            graph.viewModel.stylesState.first { it.fallbackReason != null }
        }

        assertThat(state.fallbackReason)
            .isEqualTo(StyleEditorFallbackReason.DAMAGED_SELECTION)
        assertThat(state.activeId).isEqualTo("alpha")
        assertThat(state.activeStyleName).isEqualTo("Alpha")
        assertThat(state.previewTokens).isEqualTo(StyleTokenResolver.resolve(alphaDocument))
        assertThat(state.entries.single { it.id == "broken" }.kind)
            .isEqualTo(StyleEditorEntryKind.DAMAGED)

        graph.viewModel.pick("broken")

        assertThat(graph.viewModel.stylesState.value.pendingStyleId).isNull()
        assertThat(graph.viewModel.stylesState.value.failure).isNull()
        assertThat(graph.viewModel.stylesState.value.fallbackReason)
            .isEqualTo(StyleEditorFallbackReason.DAMAGED_SELECTION)
        assertThat(withTimeout(5_000) { graph.store.activeId.first() }).isEqualTo("broken")
    }

    @Test
    fun `a damaged file exports its exact bytes and resets to the default under the same id`() =
        runTest {
            val broken = damagedFile("broken")
            val graph = graph()
            val state = withTimeout(5_000) {
                graph.viewModel.stylesState.first { listing ->
                    listing.entries.any { it.kind == StyleEditorEntryKind.DAMAGED }
                }
            }
            assertThat(state.fallbackReason).isNull()
            val original = broken.readBytes()

            graph.viewModel.exportRaw("broken")

            val exported = withTimeout(5_000) {
                graph.viewModel.stylesState.first { it.rawExport != null }
            }
            assertThat(exported.rawExport?.styleId).isEqualTo("broken")
            assertThat(exported.rawExport?.fileName).isEqualTo("broken.json")
            assertThat(exported.rawExport?.bytes).isEqualTo(original)
            assertThat(broken.readBytes()).isEqualTo(original)
            assertThat(exported.pendingCommandId).isNull()

            graph.viewModel.dismissRawExport()
            assertThat(graph.viewModel.stylesState.value.rawExport).isNull()

            graph.viewModel.beginResetDamaged("broken")
            val confirming = withTimeout(5_000) {
                graph.viewModel.stylesState.first { it.confirmTarget != null }
            }
            assertThat(confirming.confirmTarget?.action)
                .isEqualTo(StyleEditorConfirmAction.RESET_TO_DEFAULT)

            graph.viewModel.confirm()

            val reset = withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.entries.none { entry -> entry.kind == StyleEditorEntryKind.DAMAGED } &&
                        it.confirmTarget == null
                }
            }
            assertThat(reset.commandFailure).isNull()
            val expected = (StyleWriter.write(alphaDocument.copy(id = "broken"))
                as StyleWriteResult.Written).text
            assertThat(broken.readText()).isEqualTo(expected)
            assertThat(reset.entries.first { it.id == "broken" }.kind)
                .isEqualTo(StyleEditorEntryKind.VALID)
            assertThat(graph.writes.count).isEqualTo(1)
        }

    @Test
    fun `deleting a damaged file asks for the recovery confirmation and removes the file`() =
        runTest {
            val broken = damagedFile("broken")
            val graph = graph()
            withTimeout(5_000) {
                graph.viewModel.stylesState.first { listing ->
                    listing.entries.any { it.kind == StyleEditorEntryKind.DAMAGED }
                }
            }

            graph.viewModel.beginDelete("broken")

            val confirming = withTimeout(5_000) {
                graph.viewModel.stylesState.first { it.confirmTarget != null }
            }
            assertThat(confirming.confirmTarget?.action)
                .isEqualTo(StyleEditorConfirmAction.RECOVERY_DELETE)

            graph.viewModel.confirm()

            val deleted = withTimeout(5_000) {
                graph.viewModel.stylesState.first {
                    it.entries.none { entry -> entry.id == "broken" } && it.confirmTarget == null
                }
            }
            assertThat(deleted.commandFailure).isNull()
            assertThat(broken.exists()).isFalse()
            assertThat(deleted.entries.map { it.id })
                .containsExactly("alpha", "beta", "mid", "zeta")
                .inOrder()
        }
}
