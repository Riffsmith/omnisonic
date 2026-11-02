package app.omnisonic.core.data.network

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-provider readiness exposed as [StateFlow] for UI and consulted by the
 * [runGate] before sync, outbox drain and download work. Network facts come
 * from one [NetworkMonitor]; Wi-Fi-only providers need a Wi-Fi network and
 * stay blocked with [ProviderStatus.NEEDS_WIFI] on any other connected
 * network, which is distinct from [ProviderStatus.OFFLINE]. The credentials
 * marker is the T21 seam: the credentials layer reports unreadable secrets
 * here instead of the mapper failing silently.
 */
@Singleton
class ProviderStatusTracker @Inject constructor(
    private val database: OmnisonicDatabase,
    private val networkMonitor: NetworkMonitor,
) {
    private val mutex = Mutex()
    private val credentialsUnreadable = mutableSetOf<Long>()
    private val _statuses = MutableStateFlow<Map<Long, ProviderStatus>>(emptyMap())

    val statuses: StateFlow<Map<Long, ProviderStatus>> = _statuses.asStateFlow()

    /** The single gate sync, drain and download consult. */
    val runGate: ProviderRunGate = ProviderRunGate { providerId -> check(providerId) }

    /** Recomputes every provider's status; unknown providers resolve to [ProviderStatus.OK]. */
    suspend fun check(providerId: Long): ProviderStatus {
        refresh()
        return _statuses.value[providerId] ?: ProviderStatus.OK
    }

    suspend fun refresh() {
        val rows = database.providerDao().selectAll()
        val network = networkMonitor.current()
        mutex.withLock {
            _statuses.value = rows.associate { row -> row.id to statusFor(row, network) }
        }
    }

    /** T21 seam: credentials could not be unsealed; the provider shows re-enter-password. */
    suspend fun markCredentialsUnreadable(providerId: Long) {
        mutex.withLock { credentialsUnreadable += providerId }
        refresh()
    }

    suspend fun clearCredentialsUnreadable(providerId: Long) {
        mutex.withLock { credentialsUnreadable -= providerId }
        refresh()
    }

    private fun statusFor(row: ProviderEntity, network: NetworkState): ProviderStatus = when {
        row.id in credentialsUnreadable -> ProviderStatus.AUTH_FAILED
        !network.connected -> ProviderStatus.OFFLINE
        row.wifiOnly && !network.wifi -> ProviderStatus.NEEDS_WIFI
        else -> ProviderStatus.OK
    }
}
