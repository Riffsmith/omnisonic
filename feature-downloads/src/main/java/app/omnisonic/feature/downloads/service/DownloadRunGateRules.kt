package app.omnisonic.feature.downloads.service

import app.omnisonic.core.data.downloads.DownloadSettings
import app.omnisonic.core.data.downloads.OfflineStorageBlock
import app.omnisonic.core.data.downloads.OfflineStorageState
import app.omnisonic.core.data.network.NetworkState

/**
 * Global (non-provider) gates evaluated before any claim: the persisted
 * Wi-Fi-only preference and the task-2 storage readiness/free-space floor.
 * Provider/offline/auth gating stays inside the coordinator run gate, and every
 * blocked row remains `PENDING` without an error increment.
 */
object DownloadRunGateRules {

    fun block(
        settings: DownloadSettings,
        network: NetworkState,
        storage: OfflineStorageState,
    ): DownloadRunBlock? = when {
        settings.wifiOnly && !network.wifi -> DownloadRunBlock.WIFI_REQUIRED
        storage is OfflineStorageState.Blocked -> when (storage.reason) {
            OfflineStorageBlock.LOW_SPACE -> DownloadRunBlock.STORAGE_LOW_SPACE
            OfflineStorageBlock.ROOT_UNAVAILABLE,
            OfflineStorageBlock.NOT_WRITABLE,
            -> DownloadRunBlock.STORAGE_UNAVAILABLE
        }
        else -> null
    }
}
