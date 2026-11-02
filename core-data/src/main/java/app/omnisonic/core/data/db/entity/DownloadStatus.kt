package app.omnisonic.core.data.db.entity

/**
 * Lifecycle of a download row; persisted by name (T18 enum-by-name rule).
 */
enum class DownloadStatus { PENDING, RUNNING, COMPLETED, FAILED }
