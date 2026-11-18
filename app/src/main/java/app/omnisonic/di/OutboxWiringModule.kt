package app.omnisonic.di

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.queue.RoomOutboxStore
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.core.data.sync.OutboxPendingRepository
import app.omnisonic.core.data.sync.OutboxSync
import app.omnisonic.core.data.sync.OutboxSyncControl
import app.omnisonic.core.model.log.OmnisonicLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Outbox drain plumbing: the Room store, the process-scoped coordinator and the
 * screen-facing trigger seam. The scope is separate from the library walker's so
 * a pending sync never waits behind a long library scan.
 */
@Module
@InstallIn(SingletonComponent::class)
object OutboxWiringModule {

    @Provides
    @Singleton
    fun outboxStore(database: OmnisonicDatabase): RoomOutboxStore = RoomOutboxStore(database)

    @Provides
    @Singleton
    fun outboxSync(
        store: RoomOutboxStore,
        clients: ProviderClientRegistry,
        statusTracker: ProviderStatusTracker,
        pending: OutboxPendingRepository,
        logger: OmnisonicLogger,
    ): OutboxSync = OutboxSync(
        store = store,
        providerLocator = clients::providerFor,
        pending = pending,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        logger = logger,
        runGate = statusTracker.runGate,
        onAuthFailed = statusTracker::markAuthFailed,
        onAuthSucceeded = statusTracker::clearAuthFailed,
    )

    @Provides
    @Singleton
    fun outboxSyncControl(sync: OutboxSync): OutboxSyncControl = sync
}
