package app.omnisonic.playback.startup

import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.service.restoreQueueWithin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-launch entry point for the persisted queue snapshot. The shell talks to
 * the in-process controller, so nothing else starts [PlaybackController]'s
 * restore path at launch; without this the queue and mini-player only came
 * back when the system happened to start the media service.
 *
 * Runs on the engine scope (the queue machine commands the player inline) and
 * is bounded so a slow store never holds up startup. A no-op once the process
 * has already applied a snapshot — see `PlaybackController.restoreQueue`.
 */
class PlaybackLaunchRestore(
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    fun restore() {
        scope.launch { restoreQueueWithin(controller, timeoutMs) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
