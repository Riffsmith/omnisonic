package app.omnisonic.log

import app.omnisonic.core.network.NetworkLogGate
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings-backed [NetworkLogGate]. The opt-in flag is read per HTTP call, so
 * the interceptor must not cache state at client-build time; this adapter
 * keeps a volatile copy current from the DataStore flow and answers
 * [isEnabled] with a plain field read.
 */
@Singleton
class SettingsNetworkLogGate @Inject constructor(
    store: ShellPreferencesStore,
) : NetworkLogGate {

    @Volatile
    private var enabled = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            store.preferences
                .map { it.networkLoggingEnabled }
                .distinctUntilChanged()
                .collect { enabled = it }
        }
    }

    override fun isEnabled(): Boolean = enabled
}
