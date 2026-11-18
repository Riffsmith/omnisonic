package app.omnisonic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.omnisonic.core.data.sync.OutboxSyncControl
import app.omnisonic.shell.OmnisonicApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var outboxSync: Provider<OutboxSyncControl>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OmnisonicApp() }
    }

    override fun onStart() {
        super.onStart()
        outboxSync.get().requestDrain()
    }
}
