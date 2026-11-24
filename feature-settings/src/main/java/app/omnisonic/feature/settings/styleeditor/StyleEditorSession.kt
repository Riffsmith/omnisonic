package app.omnisonic.feature.settings.styleeditor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Route-scoped editor session state (D-114 Q4/Q5). TODO 5d owns the real
 * in-session Undo stack; until then nothing records an entry, so [undoCount]
 * stays zero and the hub's "Applied · Undo" affordance never appears — it is
 * shown only while an entry exists, so the editor never offers an undo it
 * cannot perform.
 */
class StyleEditorSession {

    private val _undoCount = MutableStateFlow(0)

    val undoCount: StateFlow<Int> = _undoCount.asStateFlow()

    /**
     * Records one committed command. The count is the whole stack until TODO 5d
     * owns the entries; the first edit of a built-in is the first recorded
     * command after its copy switch (D-116 Q6).
     */
    fun record() {
        _undoCount.value += 1
    }

    /** Drops the session history, for example when an edit switches to a fresh copy (D-116 Q6). */
    fun clear() {
        _undoCount.value = 0
    }

    fun undo() {
        if (_undoCount.value == 0) return
        // TODO 5d: pop the last committed change and replay its inverse through
        // the TODO 5a0 coordinator.
    }
}
