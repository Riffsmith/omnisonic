package app.omnisonic.feature.library.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.queue.RepeatMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Now-playing commands and state (UI_SPEC.md §3.8). Every transport action is
 * a button with a gesture twin on the screen; the VM only dispatches through
 * [PlaybackController] so the screen stays free of engine/session types.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    val state: StateFlow<NowPlayingUiState> = combine(
        controller.playerState,
        controller.queueState,
    ) { player, queue -> NowPlayingUiState.from(player, queue) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            NowPlayingUiState.from(controller.playerState.value, controller.queueState.value),
        )

    fun togglePlayPause() = controller.playOrPause()

    fun next() = controller.skipNext()

    fun previous() = controller.skipPrevious()

    fun seekTo(positionMs: Long) {
        val duration = state.value.durationMs
        val target = if (duration != null) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        controller.seekTo(target)
    }

    fun toggleShuffle() = controller.setShuffleEnabled(!state.value.shuffleEnabled)

    fun cycleRepeat() = controller.setRepeatMode(nextRepeatMode(state.value.repeatMode))

    /**
     * Pane state of the player surface (D-23): survives collapse because it
     * lives here, not in the sheet or the screen.
     */
    private val _pane = MutableStateFlow(NowPlayingPane.COVER)
    val pane: StateFlow<NowPlayingPane> = _pane.asStateFlow()

    private val _lyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = _lyrics.asStateFlow()

    fun togglePane() {
        _pane.update { current ->
            if (current == NowPlayingPane.COVER) NowPlayingPane.LYRICS else NowPlayingPane.COVER
        }
    }
}

internal fun nextRepeatMode(mode: RepeatMode): RepeatMode = when (mode) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}
