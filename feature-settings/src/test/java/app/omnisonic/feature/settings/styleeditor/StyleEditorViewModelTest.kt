package app.omnisonic.feature.settings.styleeditor

import app.omnisonic.core.data.style.FileStyleDerivationStore
import app.omnisonic.core.data.style.StyleAssetSource
import app.omnisonic.core.data.style.StyleMutationCoordinator
import app.omnisonic.core.data.style.StyleProfileResolver
import app.omnisonic.core.data.style.StyleProfileState
import app.omnisonic.core.data.style.StyleProfileStore
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.data.style.StyleWriteOutcome
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import app.omnisonic.style.ProfileStyleTokenSource
import app.omnisonic.style.StyleTokenResolver
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The hub's state contract (D-65/D-114 Q1): the seven areas in their stable
 * order, the active style's name and live preview tokens read from the same
 * profile-backed source the theme follows, and the Undo affordance hidden while
 * the session has no entry (TODO 5d owns the stack).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StyleEditorViewModelTest {

    @TempDir
    lateinit var tempDir: File

    private val mainDispatcher = UnconfinedTestDispatcher()

    private val logger = OmnisonicLogger(LogSink { _, _, _, _ -> })

    private val baseDocument = StyleDocument(id = "default", name = "Default")

    private lateinit var repository: StyleRepository
    private lateinit var store: StyleProfileStore

    private class MapStyleAssets(private val contents: Map<String, String>) : StyleAssetSource {
        override fun names(): List<String> = contents.keys.toList()
        override fun read(name: String): String? = contents[name]
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val baseText = (StyleWriter.write(baseDocument) as StyleWriteResult.Written).text
        repository = StyleRepository(
            stylesDir = File(tempDir, StyleRepository.STYLES_DIRECTORY),
            assets = MapStyleAssets(mapOf("default.json" to baseText)),
            logger = logger,
        )
        store = StyleProfileStore.inMemory(
            repository,
            StyleProfileResolver(repository, "default", logger),
            logger,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): StyleEditorViewModel {
        val tokenSource = ProfileStyleTokenSource(
            store.activeProfile.map { state -> (state as? StyleProfileState.Resolved)?.document },
        )
        val coordinator = StyleMutationCoordinator(
            repository = repository,
            profileStore = store,
            derivations = FileStyleDerivationStore(File(tempDir, "style-derivations.json")),
            logger = logger,
            defaultStyleId = "default",
        )
        return StyleEditorViewModel(store, tokenSource, repository, coordinator)
    }

    @Test
    fun `areas are listed in their stable order and resolve from route ids`() {
        assertThat(StyleEditorArea.entries.map { it.id }).containsExactly(
            "styles",
            "appearance",
            "home",
            "library",
            "nowPlaying",
            "importExport",
            "advanced",
        ).inOrder()
        StyleEditorArea.entries.forEach { area ->
            assertThat(StyleEditorArea.fromId(area.id)).isEqualTo(area)
        }
        assertThat(StyleEditorArea.fromId("nope")).isNull()
    }

    @Test
    fun `hub state reflects the active style name and preview tokens from the store`() = runTest {
        val viewModel = viewModel()
        val mine = StyleDocument(id = "mine", name = "Mine")
        assertThat(repository.write(mine)).isInstanceOf(StyleWriteOutcome.Written::class.java)

        val initial = withTimeout(5_000) { viewModel.hubState.first { it.activeStyleName != null } }
        assertThat(initial.activeStyleName).isEqualTo("Default")
        assertThat(initial.previewTokens).isEqualTo(StyleTokenResolver.resolve(baseDocument))
        assertThat(initial.areas).isEqualTo(StyleEditorArea.entries.toList())

        store.switch("mine")

        val switched = withTimeout(5_000) { viewModel.hubState.first { it.activeStyleName == "Mine" } }
        assertThat(switched.previewTokens).isEqualTo(StyleTokenResolver.resolve(mine))
    }

    @Test
    fun `undo affordance stays hidden while the session has no undo entry`() = runTest {
        val viewModel = viewModel()
        withTimeout(5_000) { viewModel.hubState.first { it.activeStyleName != null } }

        assertThat(viewModel.hubState.value.undoAvailable).isFalse()
        viewModel.undo()
        assertThat(viewModel.hubState.value.undoAvailable).isFalse()
    }
}
