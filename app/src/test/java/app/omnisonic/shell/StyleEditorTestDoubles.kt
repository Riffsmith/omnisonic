package app.omnisonic.shell

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.style.FileStyleDerivationStore
import app.omnisonic.core.data.style.StyleAssetSource
import app.omnisonic.core.data.style.StyleDerivationStore
import app.omnisonic.core.data.style.StyleMutationCoordinator
import app.omnisonic.core.data.style.StyleProfileResolver
import app.omnisonic.core.data.style.StyleProfileState
import app.omnisonic.core.data.style.StyleProfileStore
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.di.StyleWiringModule
import app.omnisonic.feature.settings.styleeditor.StyleEditorViewModel
import app.omnisonic.style.ProfileStyleTokenSource
import java.io.File
import kotlinx.coroutines.flow.map

/** Reads the shipped built-in style assets the way the production wiring does. */
internal class AssetStyleSource(private val assets: AssetManager) : StyleAssetSource {
    override fun names(): List<String> = assets.list("styles")?.toList().orEmpty()
    override fun read(name: String): String? = try {
        assets.open("styles/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (failure: Exception) {
        null
    }
}

/**
 * The editor graph a route test drives: the route-scoped ViewModel over an
 * in-memory profile store backed by the shipped built-ins, plus the store,
 * repository and coordinator so a test can inspect the real state the screen
 * reads (`StyleEditorStylesRoute`).
 */
internal class StyleEditorTestGraph(
    val viewModel: StyleEditorViewModel,
    val store: StyleProfileStore,
    val repository: StyleRepository,
    val coordinator: StyleMutationCoordinator,
    val derivations: StyleDerivationStore,
    val stylesDir: File,
)

internal fun testStyleEditorGraph(tag: String): StyleEditorTestGraph {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val logger = OmnisonicLogger(LogSink { _, _, _, _ -> })
    val root = File("/tmp/opencode", "style-editor-$tag-${System.nanoTime()}")
    val stylesDir = File(root, StyleRepository.STYLES_DIRECTORY)
    val repository = StyleRepository(
        stylesDir = stylesDir,
        assets = AssetStyleSource(context.assets),
        logger = logger,
    )
    val derivations = FileStyleDerivationStore(File(root, "style-derivations.json"))
    val store = StyleProfileStore.inMemory(
        repository,
        StyleProfileResolver(repository, StyleWiringModule.DEFAULT_STYLE_ID, logger),
        logger,
    )
    val coordinator = StyleMutationCoordinator(
        repository = repository,
        profileStore = store,
        derivations = derivations,
        logger = logger,
        defaultStyleId = StyleWiringModule.DEFAULT_STYLE_ID,
    )
    val tokenSource = ProfileStyleTokenSource(
        store.activeProfile.map { state -> (state as? StyleProfileState.Resolved)?.document },
    )
    return StyleEditorTestGraph(
        viewModel = StyleEditorViewModel(store, tokenSource, repository, coordinator),
        store = store,
        repository = repository,
        coordinator = coordinator,
        derivations = derivations,
        stylesDir = stylesDir,
    )
}

/** The editor ViewModel plus its store, for tests that only inspect the hub. */
internal fun testStyleEditorViewModel(tag: String): Pair<StyleEditorViewModel, StyleProfileStore> =
    testStyleEditorGraph(tag).let { graph -> graph.viewModel to graph.store }
