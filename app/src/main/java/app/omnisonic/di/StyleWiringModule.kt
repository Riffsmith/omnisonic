package app.omnisonic.di

import android.content.Context
import android.content.res.AssetManager
import app.omnisonic.core.data.style.FileStyleDerivationStore
import app.omnisonic.core.data.style.StyleAssetSource
import app.omnisonic.core.data.style.StyleDerivationStore
import app.omnisonic.core.data.style.StyleMutationCoordinator
import app.omnisonic.core.data.style.StyleProfileResolver
import app.omnisonic.core.data.style.StyleProfileState
import app.omnisonic.core.data.style.StyleProfileStore
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.style.ObservableStyleTokenSource
import app.omnisonic.style.ProfileStyleTokenSource
import app.omnisonic.style.StyleTokenSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/**
 * Production seam for the profile-backed tokens (`docs/omnisonic/style/style-profiles.md`
 * §Wiring, D-79). Built-ins are the raw assets shipped by `:style` (D-77); the
 * active document comes from [StyleProfileStore] (D-73) and resolves through
 * [app.omnisonic.style.StyleTokenResolver] (D-78). Screens keep reading
 * `OmnisonicTheme`; TODO 4d owns observing this flow for repaint.
 */
@Module
@InstallIn(SingletonComponent::class)
object StyleWiringModule {

    /**
     * The built-in a first run resolves, and the fallback base for every profile:
     * the approved baseline whose resolved tokens equal `DefaultStyleTokens`
     * (`docs/omnisonic/style/style-profiles.md` §Baseline, D-105).
     */
    const val DEFAULT_STYLE_ID: String = "builtin-default"

    private const val ASSET_DIRECTORY = "styles"

    @Provides
    @Singleton
    fun styleAssetSource(@ApplicationContext context: Context): StyleAssetSource =
        AssetManagerStyleAssetSource(context.assets)

    @Provides
    @Singleton
    fun styleRepository(
        @ApplicationContext context: Context,
        assets: StyleAssetSource,
        logger: OmnisonicLogger,
    ): StyleRepository = StyleRepository(
        stylesDir = File(context.filesDir, StyleRepository.STYLES_DIRECTORY),
        assets = assets,
        logger = logger,
    )

    @Provides
    @Singleton
    fun styleProfileResolver(
        repository: StyleRepository,
        logger: OmnisonicLogger,
    ): StyleProfileResolver = StyleProfileResolver(repository, DEFAULT_STYLE_ID, logger)

    @Provides
    @Singleton
    fun styleProfileStore(
        @ApplicationContext context: Context,
        repository: StyleRepository,
        resolver: StyleProfileResolver,
        logger: OmnisonicLogger,
    ): StyleProfileStore = StyleProfileStore.create(context, repository, resolver, logger)

    /**
     * Derivation metadata lives beside the private files directory, never under
     * `styles/`, so the style listing cannot pick it up (D-82/D-108).
     */
    @Provides
    @Singleton
    fun styleDerivationStore(@ApplicationContext context: Context): StyleDerivationStore =
        FileStyleDerivationStore(File(context.filesDir, "style-derivations.json"))

    /** The one ordered boundary every style write and pointer change goes through (D-108). */
    @Provides
    @Singleton
    fun styleMutationCoordinator(
        repository: StyleRepository,
        store: StyleProfileStore,
        derivations: StyleDerivationStore,
        logger: OmnisonicLogger,
    ): StyleMutationCoordinator = StyleMutationCoordinator(
        repository = repository,
        profileStore = store,
        derivations = derivations,
        logger = logger,
        defaultStyleId = DEFAULT_STYLE_ID,
    )

    /**
     * A profile the resolver could not turn into a document (`Unavailable`) maps
     * to `null`, which [ProfileStyleTokenSource] skips; the last good tokens stay.
     */
    @Provides
    @Singleton
    fun profileStyleTokenSource(store: StyleProfileStore): ProfileStyleTokenSource =
        ProfileStyleTokenSource(
            activeDocument = store.activeProfile.map { state ->
                when (state) {
                    is StyleProfileState.Resolved -> state.document
                    is StyleProfileState.Unavailable -> null
                }
            },
        )

    @Provides
    @Singleton
    fun styleTokenSource(source: ProfileStyleTokenSource): StyleTokenSource = source

    /** The observable view the root theme collects for a live repaint (TODO 4d). */
    @Provides
    @Singleton
    fun observableStyleTokenSource(source: ProfileStyleTokenSource): ObservableStyleTokenSource = source

    private class AssetManagerStyleAssetSource(
        private val assets: AssetManager,
    ) : StyleAssetSource {

        override fun names(): List<String> = assets.list(ASSET_DIRECTORY)?.toList().orEmpty()

        override fun read(name: String): String? = try {
            assets.open("$ASSET_DIRECTORY/$name")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (failure: IOException) {
            null
        } catch (failure: SecurityException) {
            null
        }
    }
}
