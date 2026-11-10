package app.omnisonic.di

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.core.data.providers.InitialLibrarySync
import app.omnisonic.core.data.providers.LibrarySyncLauncher
import app.omnisonic.core.data.providers.ProviderBlockPreferenceStore
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.core.data.providers.ProviderConfigMapper
import app.omnisonic.core.data.security.CredentialCodec
import app.omnisonic.core.data.sync.LibrarySyncControl
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.runCatchingCancellable
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.local.LocalMediaProviderFactory
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import android.content.Context
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Wiring for the provider client registry (T12/T21) and its first production consumer. */
@Module
@InstallIn(SingletonComponent::class)
object ProviderWiringModule {

    private const val PROVIDER_WIRING_TAG = "ProviderWiring"

    /**
     * The block-preference callback persists through the real DAO. Persisting
     * only `lastWorkingBlock` (never `updatedAt`) keeps the registry cache key
     * intact, so a failover does not rebuild the provider or its clients. A
     * failed write is logged and best-effort: the in-memory failover still
     * completes the caller's request.
     */
    @Provides
    @Singleton
    fun providerRegistry(
        @ApplicationContext context: Context,
        blockPreferences: ProviderBlockPreferenceStore,
        statusTracker: ProviderStatusTracker,
        logger: OmnisonicLogger,
    ): ProviderRegistry =
        ProviderRegistry(
            listOf(
                SubsonicProviderFactory(
                    onPreferredBlockChange = { providerId, block ->
                        runCatchingCancellable {
                            blockPreferences.persist(providerId, block)
                        }.onFailure { failure ->
                            logger.warn(
                                PROVIDER_WIRING_TAG,
                                "failed to persist the preferred block for provider $providerId",
                                failure,
                            )
                        }
                    },
                    onAuthSucceeded = statusTracker::clearAuthFailed,
                ),
                LocalMediaProviderFactory(context.contentResolver),
            ),
        )

    @Provides
    @Singleton
    fun providerConfigMapper(codec: CredentialCodec, moshi: Moshi): ProviderConfigMapper =
        ProviderConfigMapper(codec, moshi)

    @Provides
    @Singleton
    fun providerClientRegistry(
        database: OmnisonicDatabase,
        providerRegistry: ProviderRegistry,
        mapper: ProviderConfigMapper,
        statusTracker: ProviderStatusTracker,
        logger: OmnisonicLogger,
    ): ProviderClientRegistry =
        ProviderClientRegistry(
            database = database,
            providerRegistry = providerRegistry,
            mapper = mapper,
            logger = logger,
            onCredentialsUnreadable = { providerId ->
                statusTracker.markCredentialsUnreadable(providerId)
            },
        )

    /**
     * First library walk after setup. Runs on its own process-scoped coroutine
     * scope so leaving the setup screen cannot cancel it; failures are logged
     * and auth outcomes feed the status tracker's run gate.
     */
    @Provides
    @Singleton
    fun librarySyncLauncher(
        database: OmnisonicDatabase,
        clients: ProviderClientRegistry,
        providerRegistry: ProviderRegistry,
        statusTracker: ProviderStatusTracker,
        statuses: LibrarySyncStatusRepository,
        logger: OmnisonicLogger,
    ): LibrarySyncLauncher =
        LibrarySyncLauncher(
            database = database,
            clients = clients,
            traitsFor = providerRegistry::traitsFor,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            logger = logger,
            runGate = statusTracker.runGate,
            onAuthFailed = statusTracker::markAuthFailed,
            onAuthSucceeded = statusTracker::clearAuthFailed,
            statuses = statuses,
        )

    @Provides
    @Singleton
    fun initialLibrarySync(launcher: LibrarySyncLauncher): InitialLibrarySync = launcher

    /** Screen-facing sync seam for the first-run step and the settings manager. */
    @Provides
    @Singleton
    fun librarySyncControl(launcher: LibrarySyncLauncher): LibrarySyncControl = launcher
}
