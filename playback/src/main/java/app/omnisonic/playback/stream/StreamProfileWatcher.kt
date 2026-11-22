package app.omnisonic.playback.stream

import app.omnisonic.core.data.network.NetworkStateObserver
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.playback.api.queue.QueuePlayer
import app.omnisonic.playback.transcode.TranscodeDecision
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Re-requests the current stream when its effective profile changes mid-track
 * (D-61). The local renderer's settings flow and the network-state stream feed
 * the same [StreamPlanSource] the resolver uses, so a changed plan decision for
 * the same queue entry commands [QueuePlayer.replanCurrentStream] — a network
 * transition (Wi-Fi ↔ cellular/VPN/metered) or a transcode-settings edit. Only
 * an actively playing entry is replanned; the queue machine applies its own
 * no-op guards (idle, radio, direct-URI/offline stream).
 */
class StreamProfileWatcher @Inject constructor(
    private val settings: TranscodeSettingsStore,
    private val networkStates: NetworkStateObserver,
    private val plans: StreamPlanSource,
    private val queue: QueuePlayer,
    private val isPlaying: () -> Boolean,
) {
    private var last: Pair<Long, TranscodeDecision>? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(
            settings.settingsFor(TranscodeRendererId.Local),
            networkStates.states(),
            queue.state,
        ) { current, network, state -> Triple(current, network, state) }
            .collect { (current, network, state) ->
                val entry = state.currentEntry
                if (entry == null) {
                    last = null
                    return@collect
                }
                val decision = plans.plan(current, network, entry)
                val key = entry.queueItemId to decision
                val previous = last
                last = key
                if (previous != null &&
                    previous.first == entry.queueItemId &&
                    previous.second != decision &&
                    isPlaying()
                ) {
                    queue.replanCurrentStream()
                }
            }
    }
}
