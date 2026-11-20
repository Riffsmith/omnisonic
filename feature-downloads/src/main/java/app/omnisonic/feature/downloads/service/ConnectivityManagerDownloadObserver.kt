package app.omnisonic.feature.downloads.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One default-network callback per download session. Events are coalesced by
 * the runtime's conflated wake channel, so a burst of capability changes only
 * rechecks the run gate once.
 */
@Singleton
class ConnectivityManagerDownloadObserver @Inject constructor(
    @ApplicationContext context: Context,
) : DownloadConnectivityObserver {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onChange: () -> Unit) {
        synchronized(lock) {
            if (callback != null) return
            val registered = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()

                override fun onLost(network: Network) = onChange()

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = onChange()
            }
            try {
                connectivityManager?.registerDefaultNetworkCallback(registered)
                callback = registered
            } catch (_: SecurityException) {
                callback = null
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val current = callback ?: return
            callback = null
            runCatching { connectivityManager?.unregisterNetworkCallback(current) }
        }
    }
}
