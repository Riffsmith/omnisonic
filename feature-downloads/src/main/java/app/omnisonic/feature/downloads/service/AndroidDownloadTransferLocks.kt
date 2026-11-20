package app.omnisonic.feature.downloads.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-transfer partial wake lock plus a Wi-Fi high-performance lock. The Wi-Fi
 * lock is only taken when the radio reports enabled, which is why the module
 * manifest declares `ACCESS_WIFI_STATE`; `WAKE_LOCK` alone does not expose
 * that state. Both locks are unrefcounted and released exactly once.
 */
@Singleton
class AndroidDownloadTransferLocks @Inject constructor(
    @ApplicationContext context: Context,
) : DownloadTransferLocks {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    override fun acquire(rowId: Long): DownloadTransferLock {
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$WAKE_TAG:$rowId",
        )?.apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
        val wifiLock = if (wifiManager?.isWifiEnabled == true) {
            wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$WIFI_TAG:$rowId",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            null
        }
        return ReleasedOnce(wakeLock, wifiLock)
    }

    private class ReleasedOnce(
        private val wakeLock: PowerManager.WakeLock?,
        private val wifiLock: WifiManager.WifiLock?,
    ) : DownloadTransferLock {

        private val released = AtomicBoolean(false)

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            runCatching { wifiLock?.release() }
            runCatching { wakeLock?.release() }
        }
    }

    private companion object {
        const val WAKE_TAG = "app.omnisonic:download"
        const val WIFI_TAG = "app.omnisonic:download-wifi"
        const val WAKE_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
