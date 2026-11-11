package app.omnisonic.feature.library.miniplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.playback.api.controller.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Mini-player state and commands (UI_SPEC.md §3.10). Shell-hoisted so the bar
 * survives tab and detail navigation; commands go through [PlaybackController]
 * so the bar never sees engine or session types.
 */
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    val state: StateFlow<MiniPlayerUiState> = combine(
        controller.playerState,
        controller.queueState,
    ) { player, queue -> MiniPlayerUiState.from(player, queue) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            MiniPlayerUiState.from(controller.playerState.value, controller.queueState.value),
        )

    fun togglePlayPause() = controller.playOrPause()

    fun next() = controller.skipNext()
}
