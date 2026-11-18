package app.omnisonic.core.data.sync

import app.omnisonic.core.data.db.OmnisonicDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One provider's queued user-data changes, as the settings view renders them. */
data class OutboxPendingProvider(
    val providerId: Long,
    val providerName: String?,
    val pendingCount: Int,
    val failingCount: Int,
    val lastError: String?,
)

/**
 * Read model over the outbox for the "pending sync" view. Counts and errors are
 * aggregate queries, so opening or writing to the settings screen never loads
 * one row per queued change.
 */
@Singleton
class OutboxPendingRepository @Inject constructor(
    private val database: OmnisonicDatabase,
) {

    val providers: Flow<List<OutboxPendingProvider>> = combine(
        database.outboxDao().observeProviderCounts(),
        database.outboxDao().observeProviderErrors(),
        database.providerDao().observeIdentities(),
    ) { counts, errors, identities ->
        val errorsByProvider = errors.associateBy { it.providerId }
        val namesById = identities.associate { it.id to it.name }
        val order = identities.mapIndexed { index, identity -> identity.id to index }.toMap()
        counts
            .sortedBy { order[it.providerId] ?: Int.MAX_VALUE }
            .map { row ->
                OutboxPendingProvider(
                    providerId = row.providerId,
                    providerName = namesById[row.providerId],
                    pendingCount = row.pendingCount,
                    failingCount = row.failingCount,
                    lastError = errorsByProvider[row.providerId]?.lastError,
                )
            }
    }

    /** Makes every parked row due again; a manual retry reads the rows straight after. */
    suspend fun clearBackoff() {
        database.outboxDao().clearAllBackoff()
    }
}
