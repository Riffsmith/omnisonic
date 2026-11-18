package app.omnisonic.core.data.history

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-provider recording pause (D-7). Process-local until the history
 * settings store lands (Phase 6 task 9); the write path reads it on every
 * record call, so a paused provider keeps its existing rows untouched.
 */
@Singleton
class HistoryRecordingGate @Inject constructor() {

    private val _pausedProviders = MutableStateFlow<Set<Long>>(emptySet())

    val pausedProviders: StateFlow<Set<Long>> = _pausedProviders.asStateFlow()

    fun isPaused(providerId: Long): Boolean = providerId in _pausedProviders.value

    fun setPaused(providerId: Long, paused: Boolean) {
        _pausedProviders.update { current ->
            if (paused) current + providerId else current - providerId
        }
    }
}
