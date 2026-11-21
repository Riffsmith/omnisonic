package app.omnisonic.feature.settings.downloads

import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadRootState
import app.omnisonic.core.data.downloads.DownloadSettings
import app.omnisonic.core.data.downloads.OfflineRoot
import app.omnisonic.core.data.downloads.OfflineRootChangeResult
import app.omnisonic.core.data.downloads.OfflineRootSelection
import app.omnisonic.core.data.downloads.OfflineRootWorkGate
import app.omnisonic.core.data.downloads.OfflineStorage
import kotlinx.coroutines.flow.Flow

/**
 * Production settings port. Root changes use [OfflineStorage.changeRoot] with
 * the runtime as [OfflineRootWorkGate], so the verified copy/publish/cleanup
 * journal — not this UI — owns move safety and recovery.
 */
class CoreDownloadSettingsPort(
    private val store: DownloadQueueSettingsStore,
    private val storage: OfflineStorage,
    private val gate: OfflineRootWorkGate,
) : DownloadSettingsPort {

    override val settings: Flow<DownloadSettings> = store.settings

    override val rootState: Flow<DownloadRootState> = store.rootState

    override fun availableRoots(): List<OfflineRoot> = storage.availableRoots()

    override suspend fun setWorkerCount(count: Int): Unit {
        store.setWorkerCount(count)
    }

    override suspend fun setWifiOnly(enabled: Boolean): Unit {
        store.setWifiOnly(enabled)
    }

    override suspend fun changeRoot(selection: OfflineRootSelection): OfflineRootChangeResult =
        storage.changeRoot(selection, gate)
}
