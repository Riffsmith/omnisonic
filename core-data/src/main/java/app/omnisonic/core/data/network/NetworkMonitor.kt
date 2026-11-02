package app.omnisonic.core.data.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** One snapshot of connectivity; the single source for run-gate decisions. */
data class NetworkState(
    val connected: Boolean,
    val wifi: Boolean,
)

/** Connectivity snapshotter; injectable so tests substitute fakes. */
fun interface NetworkMonitor {
    fun current(): NetworkState
}

/**
 * Reads [ConnectivityManager] active-network capabilities. A VPN hides its
 * underlying transport and the platform exposes no public lookup for it, so
 * a VPN-only network is treated as NOT Wi-Fi: a metered connection is never
 * assumed to be Wi-Fi, so Wi-Fi-only providers may stay blocked behind a VPN.
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
        )
    }
}
