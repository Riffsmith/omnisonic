package app.omnisonic.core.data.network

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.provider.api.ProviderStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderStatusTrackerTest : DatabaseTestBase() {

    private class FakeMonitor(var state: NetworkState) : NetworkMonitor {
        override fun current(): NetworkState = state
    }

    private suspend fun wifiOnlyProvider(id: Long = 1): ProviderEntity =
        newProvider(id = id).let { seeded -> providerDao.upsert(seeded.copy(wifiOnly = true)); providerDao.selectById(id)!! }

    private fun tracker(monitor: FakeMonitor) = ProviderStatusTracker(db, monitor)

    @Test
    fun offlineNetworkReportsOfflineForEveryProvider() = runDbTest {
        wifiOnlyProvider(id = 1)
        newProvider(id = 2)
        val tracker = tracker(FakeMonitor(NetworkState(connected = false, wifi = false)))

        tracker.refresh()

        assertThat(tracker.statuses.value).containsExactlyEntriesIn(
            mapOf(1L to ProviderStatus.OFFLINE, 2L to ProviderStatus.OFFLINE),
        )
        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.OFFLINE)
    }

    @Test
    fun connectedNonWifiNetworkBlocksOnlyWifiOnlyProviders() = runDbTest {
        wifiOnlyProvider(id = 1)
        newProvider(id = 2)
        val tracker = tracker(FakeMonitor(NetworkState(connected = true, wifi = false)))

        tracker.refresh()

        assertThat(tracker.statuses.value[1]).isEqualTo(ProviderStatus.NEEDS_WIFI)
        assertThat(tracker.statuses.value[2]).isEqualTo(ProviderStatus.OK)
    }

    @Test
    fun connectedWifiNetworkAllowsWifiOnlyProviders() = runDbTest {
        wifiOnlyProvider(id = 1)
        val tracker = tracker(FakeMonitor(NetworkState(connected = true, wifi = true)))

        tracker.refresh()

        assertThat(tracker.statuses.value[1]).isEqualTo(ProviderStatus.OK)
    }

    @Test
    fun unreadableCredentialsMarkAuthFailedUntilCleared() = runDbTest {
        newProvider(id = 1)
        val tracker = tracker(FakeMonitor(NetworkState(connected = true, wifi = true)))

        tracker.markCredentialsUnreadable(1)
        assertThat(tracker.statuses.value[1]).isEqualTo(ProviderStatus.AUTH_FAILED)
        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.AUTH_FAILED)

        tracker.clearCredentialsUnreadable(1)
        assertThat(tracker.statuses.value[1]).isEqualTo(ProviderStatus.OK)
    }

    @Test
    fun authFailedWinsOverNetworkStates() = runDbTest {
        wifiOnlyProvider(id = 1)
        val tracker = tracker(FakeMonitor(NetworkState(connected = false, wifi = false)))

        tracker.markCredentialsUnreadable(1)

        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.AUTH_FAILED)
    }

    @Test
    fun serverAuthFailureMarksAuthFailedUntilCleared() = runDbTest {
        newProvider(id = 1)
        val tracker = tracker(FakeMonitor(NetworkState(connected = true, wifi = true)))

        tracker.markAuthFailed(1)
        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.AUTH_FAILED)

        tracker.clearAuthFailed(1)
        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.OK)
    }

    @Test
    fun editingTheProviderClearsServerAuthFailure() = runDbTest {
        newProvider(id = 1)
        val tracker = tracker(FakeMonitor(NetworkState(connected = true, wifi = true)))
        tracker.markAuthFailed(1)
        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.AUTH_FAILED)

        val row = providerDao.selectById(1)!!
        providerDao.upsert(row.copy(username = "fixed-user", updatedAt = row.updatedAt + 1))

        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.OK)
    }

    @Test
    fun unknownProviderResolvesToOk() = runDbTest {
        val tracker = tracker(FakeMonitor(NetworkState(connected = false, wifi = false)))

        assertThat(tracker.check(99)).isEqualTo(ProviderStatus.OK)
    }

    @Test
    fun refreshPicksUpRowEdits() = runDbTest {
        newProvider(id = 1)
        val monitor = FakeMonitor(NetworkState(connected = true, wifi = false))
        val tracker = tracker(monitor)
        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.OK)

        val row = providerDao.selectById(1)!!
        providerDao.upsert(row.copy(wifiOnly = true, updatedAt = row.updatedAt + 1))
        assertThat(tracker.check(1)).isEqualTo(ProviderStatus.NEEDS_WIFI)
    }

    @Test
    fun runGateSurfacesBlockedStatuses() = runDbTest {
        wifiOnlyProvider(id = 1)
        val tracker = tracker(FakeMonitor(NetworkState(connected = true, wifi = false)))

        assertThat(tracker.runGate.check(1)).isEqualTo(ProviderStatus.NEEDS_WIFI)
    }
}
