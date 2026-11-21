package app.omnisonic.feature.settings.downloads

import app.omnisonic.core.data.downloads.DownloadRootState
import app.omnisonic.core.data.downloads.DownloadSettings
import app.omnisonic.core.data.downloads.OfflineRoot
import app.omnisonic.core.data.downloads.OfflineRootChangeResult
import app.omnisonic.core.data.downloads.OfflineRootSelection
import kotlinx.coroutines.flow.Flow

/** Download settings/storage commands used by the settings surface (task 9d). */
interface DownloadSettingsPort {

    val settings: Flow<DownloadSettings>

    val rootState: Flow<DownloadRootState>

    fun availableRoots(): List<OfflineRoot>

    suspend fun setWorkerCount(count: Int)

    suspend fun setWifiOnly(enabled: Boolean)

    suspend fun changeRoot(selection: OfflineRootSelection): OfflineRootChangeResult
}
