package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.OmnisonicDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence seam for the failover block a provider learned at runtime.
 * Writes only `lastWorkingBlock`; `updatedAt` is deliberately untouched so
 * [ProviderClientRegistry]'s row cache key stays stable across preferences.
 */
@Singleton
class ProviderBlockPreferenceStore @Inject constructor(
    private val database: OmnisonicDatabase,
) {
    suspend fun persist(providerId: Long, block: Int) {
        database.providerDao().setLastWorkingBlock(providerId, block)
    }
}
