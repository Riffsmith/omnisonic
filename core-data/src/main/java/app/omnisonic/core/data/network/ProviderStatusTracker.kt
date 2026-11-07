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
 * network, which is distinct from [ProviderStatus.OFFLINE].
 *
 * An authentication failure is either unreadable local credentials or a
 * server rejection reported as [app.omnisonic.provider.api.ProviderAuthException].
 * The marker remembers the provider row's `updatedAt`; editing the row clears
 * it, as does [clearAuthFailed] after a successful provider call.
 */
@Singleton
class ProviderStatusTracker @Inject constructor(
    private val database: OmnisonicDatabase,
    private val networkMonitor: NetworkMonitor,
) {
    private data class AuthFailure(val providerUpdatedAt: Long)

    private val mutex = Mutex()
    private val authFailures = mutableMapOf<Long, AuthFailure>()
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
        val rowsById = rows.associateBy { it.id }
        mutex.withLock {
            authFailures.entries.removeAll { (providerId, failure) ->
                val row = rowsById[providerId]
                row == null || row.updatedAt != failure.providerUpdatedAt
            }
            _statuses.value = rows.associate { row -> row.id to statusFor(row, network) }
        }
    }

    /** The server rejected this provider's credentials; retry work is blocked. */
    suspend fun markAuthFailed(providerId: Long) {
        val row = database.providerDao().selectById(providerId) ?: return
        mutex.withLock { authFailures[providerId] = AuthFailure(row.updatedAt) }
        refresh()
    }

    /** A provider call authenticated successfully; allow retry work again. */
    suspend fun clearAuthFailed(providerId: Long) {
        val cleared = mutex.withLock { authFailures.remove(providerId) != null }
        if (cleared) refresh()
    }

    /** T21 seam: credentials could not be unsealed; the provider shows re-enter-password. */
    suspend fun markCredentialsUnreadable(providerId: Long) = markAuthFailed(providerId)

    suspend fun clearCredentialsUnreadable(providerId: Long) = clearAuthFailed(providerId)

    private fun statusFor(row: ProviderEntity, network: NetworkState): ProviderStatus = when {
        row.id in authFailures -> ProviderStatus.AUTH_FAILED
        !network.connected -> ProviderStatus.OFFLINE
        row.wifiOnly && !network.wifi -> ProviderStatus.NEEDS_WIFI
        else -> ProviderStatus.OK
    }
}
