package app.omnisonic.core.data.sync

/**
 * Live progress of one library walk (D-16). The walk publishes a value per
 * phase; [ALBUMS_SONGS] additionally carries the per-album item counter. The
 * persisted `sync_state` row stays the record of the last outcome, so this
 * model never touches the schema.
 */
enum class LibrarySyncPhase {
    ARTISTS,
    ALBUMS_SONGS,
    GENRES,
    RADIOS,
    PLAYLISTS,
    BOOKMARKS,
    PRUNE,
    DONE,
    FAILED,
    BLOCKED,
    SKIPPED,
    ;

    val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == BLOCKED || this == SKIPPED
}

enum class LibrarySyncOutcome {
    COMPLETED,
    FAILED,
    BLOCKED,
    SKIPPED,
}

data class LibrarySyncProgress(
    val providerId: Long,
    val phase: LibrarySyncPhase,
    val startedAt: Long,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val finishedAt: Long? = null,
    val outcome: LibrarySyncOutcome? = null,
    val message: String? = null,
) {

    init {
        require(itemsDone >= 0) { "itemsDone must not be negative" }
        require(itemsTotal >= 0) { "itemsTotal must not be negative" }
        require(phase.isTerminal == (outcome != null)) {
            "terminal phases need an outcome and running phases must not carry one"
        }
        require(phase.isTerminal == (finishedAt != null)) {
            "terminal phases close the walk and running phases stay open"
        }
    }

    val isRunning: Boolean
        get() = outcome == null

    /** Null while the phase reports no count (indeterminate progress). */
    val fraction: Float?
        get() = if (itemsTotal > 0) {
            (itemsDone.toFloat() / itemsTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}
