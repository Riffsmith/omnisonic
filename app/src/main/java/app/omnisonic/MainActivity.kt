package app.omnisonic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import app.omnisonic.core.data.sync.OutboxSyncControl
import app.omnisonic.feature.downloads.service.DownloadNotificationActions
import app.omnisonic.feature.downloads.service.DownloadServiceStarter
import app.omnisonic.shell.OmnisonicApp
import app.omnisonic.style.ObservableStyleTokenSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var outboxSync: Provider<OutboxSyncControl>

    @Inject
    lateinit var downloadStarter: Provider<DownloadServiceStarter>

    @Inject
    lateinit var styleTokenSource: ObservableStyleTokenSource

    /** A notification tap is a one-shot request; the shell reports consumption. */
    private val openDownloads = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val request by openDownloads.collectAsState()
            OmnisonicApp(
                openDownloadsRequest = request,
                onOpenDownloadsHandled = { openDownloads.value = false },
                tokenSource = styleTokenSource,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        outboxSync.get().requestDrain()
        downloadStarter.get().start()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == DownloadNotificationActions.ACTION_OPEN_DOWNLOADS) {
            openDownloads.value = true
        }
    }
}
