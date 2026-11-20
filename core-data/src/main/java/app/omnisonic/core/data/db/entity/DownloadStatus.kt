package app.omnisonic.core.data.db.entity

/**
 * Lifecycle of a download row, persisted by name. There is no completed state:
 * a finished transfer or an explicit cancel deletes the row
 * (`.agents/plans/phase-7-tasks.md` §Task 1 lifecycle).
 */
enum class DownloadStatus { PENDING, PAUSED, USER_PAUSED, ACTIVE, FAILED }
