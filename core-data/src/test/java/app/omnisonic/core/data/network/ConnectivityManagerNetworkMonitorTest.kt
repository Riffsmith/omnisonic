package app.omnisonic.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityManagerNetworkMonitorTest {

    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    }

    private fun installCapabilities(build: org.robolectric.shadows.ShadowNetworkCapabilities.() -> Unit) {
        val network = connectivityManager.activeNetwork ?: error("shadow default state has no active network")
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).build()
        shadowOf(connectivityManager).setNetworkCapabilities(network, capabilities)
    }

    private fun monitor() = ConnectivityManagerNetworkMonitor(connectivityManager)

    @Test
    fun wifiWithInternetCapabilityReadsAsConnectedWifi() {
        installCapabilities {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }

        assertThat(monitor().current()).isEqualTo(NetworkState(connected = true, wifi = true))
    }

    @Test
    fun cellularReadsAsConnectedNonWifi() {
        installCapabilities {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        }

        assertThat(monitor().current()).isEqualTo(NetworkState(connected = true, wifi = false))
    }

    @Test
    fun vpnOnlyNetworkReadsAsNonWifiBecauseUnderlyingTransportIsNotPublic() {
        installCapabilities {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        }

        assertThat(monitor().current()).isEqualTo(NetworkState(connected = true, wifi = false))
    }

    @Test
    fun missingInternetCapabilityReadsAsDisconnected() {
        installCapabilities {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }

        assertThat(monitor().current()).isEqualTo(NetworkState(connected = false, wifi = true))
    }

    @Test
    fun missingCapabilitiesReadAsDisconnected() {
        val network = connectivityManager.activeNetwork ?: error("no active network in shadow default state")
        shadowOf(connectivityManager).setNetworkCapabilities(network, null)

        assertThat(monitor().current()).isEqualTo(NetworkState(connected = false, wifi = false))
    }

    @Test
    fun noActiveNetworkReadsAsDisconnected() {
        shadowOf(connectivityManager).setActiveNetworkInfo(null)

        assertThat(monitor().current()).isEqualTo(NetworkState(connected = false, wifi = false))
    }
}
