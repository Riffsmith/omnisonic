package app.omnisonic.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityManagerNetworkStateObserverTest {

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

    private fun wifi() = installCapabilities {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun cellular() = installCapabilities {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun fireDefaultCallbacks() {
        val network = connectivityManager.activeNetwork ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        shadowOf(connectivityManager).networkCallbacks.forEach { it.onCapabilitiesChanged(network, capabilities) }
    }

    private fun observer() = ConnectivityManagerNetworkStateObserver(
        connectivityManager,
        ConnectivityManagerNetworkMonitor(connectivityManager),
    )

    @Test
    fun emitsTheCurrentSnapshotFirstThenEveryDefaultNetworkChange() = runTest {
        wifi()
        val seen = mutableListOf<NetworkState>()
        val job = launch(Dispatchers.Unconfined) { observer().states().toList(seen) }
        advanceUntilIdle()

        assertThat(seen).containsExactly(NetworkState(connected = true, wifi = true, metered = false))

        cellular()
        fireDefaultCallbacks()
        advanceUntilIdle()

        assertThat(seen).containsExactly(
            NetworkState(connected = true, wifi = true, metered = false),
            NetworkState(connected = true, wifi = false, metered = true),
        )
        job.cancel()
    }

    @Test
    fun consecutiveDuplicateSnapshotsAreSuppressed() = runTest {
        wifi()
        val seen = mutableListOf<NetworkState>()
        val job = launch(Dispatchers.Unconfined) { observer().states().toList(seen) }
        advanceUntilIdle()

        fireDefaultCallbacks()
        fireDefaultCallbacks()
        advanceUntilIdle()

        assertThat(seen).hasSize(1)
        job.cancel()
    }

    @Test
    fun collectionCancellationUnregistersTheCallback() = runTest {
        wifi()
        val job = launch(Dispatchers.Unconfined) { observer().states().collect { } }
        advanceUntilIdle()
        assertThat(shadowOf(connectivityManager).networkCallbacks).isNotEmpty()

        job.cancel()
        advanceUntilIdle()

        assertThat(shadowOf(connectivityManager).networkCallbacks).isEmpty()
    }
}
