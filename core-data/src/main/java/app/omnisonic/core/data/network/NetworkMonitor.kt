package app.omnisonic.core.data.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** One snapshot of connectivity; the single source for run-gate decisions. */
data class NetworkState(
    val connected: Boolean,
    val wifi: Boolean,
    val metered: Boolean = false,
    val vpn: Boolean = false,
)

/** Connectivity snapshotter; injectable so tests substitute fakes. */
fun interface NetworkMonitor {
    fun current(): NetworkState
}

/**
 * Stream of connectivity snapshots: the current state first, then every
 * default-network change, with consecutive duplicates suppressed. Snapshots
 * are recomputed through [NetworkMonitor], so observers and one-shot readers
 * always see the same values. Injectability lets tests substitute fakes.
 */
fun interface NetworkStateObserver {
    fun states(): Flow<NetworkState>
}

/**
 * [NetworkStateObserver] over the default-network callback. Every callback
 * event re-reads [snapshots], because the active network may already have
 * changed by the time the event fires.
 */
class ConnectivityManagerNetworkStateObserver(
    private val connectivityManager: ConnectivityManager,
    private val snapshots: NetworkMonitor,
) : NetworkStateObserver {

    override fun states(): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(snapshots.current())
            }

            override fun onLost(network: Network) {
                trySend(snapshots.current())
            }

            override fun onUnavailable() {
                trySend(snapshots.current())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(snapshots.current())
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        trySend(snapshots.current())
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/**
 * Reads [ConnectivityManager] active-network capabilities. A VPN hides its
 * underlying transport and the platform exposes no public lookup for it, so
 * a VPN-only network is treated as NOT Wi-Fi: a metered connection is never
 * assumed to be Wi-Fi, so Wi-Fi-only providers may stay blocked behind a VPN.
 * `metered` and `vpn` are reported for policy consumers (D-60); run-gate
 * decisions keep reading only `connected` and `wifi`.
 */
class ConnectivityManagerNetworkMonitor(
    private val connectivityManager: ConnectivityManager,
) : NetworkMonitor {

    override fun current(): NetworkState {
        val network: Network? = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            ?: return NetworkState(connected = false, wifi = false)
        return NetworkState(
            connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }
}
