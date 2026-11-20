package app.omnisonic.core.data.downloads

import java.io.File

/**
 * User-facing download preferences persisted in `omnisonic_downloads`
 * (`.agents/plans/phase-7-tasks.md` §Task 2, D-40). The worker count is clamped
 * to 1–4 by [DownloadSettingsRules] on both write and decode, so a corrupt value
 * can never start more transfers than the approved cap.
 */
data class DownloadSettings(
    val workerCount: Int = DownloadSettingsRules.DEFAULT_WORKER_COUNT,
    val wifiOnly: Boolean = DownloadSettingsRules.DEFAULT_WIFI_ONLY,
)

object DownloadSettingsRules {

    const val MIN_WORKER_COUNT = 1
    const val MAX_WORKER_COUNT = 4
    const val DEFAULT_WORKER_COUNT = 2
    const val DEFAULT_WIFI_ONLY = true

    fun clampWorkerCount(requested: Int): Int =
        requested.coerceIn(MIN_WORKER_COUNT, MAX_WORKER_COUNT)
}

/**
 * The user's preferred storage root. [Auto] prefers the primary app-private
 * external directory and falls back to internal files at first publication;
 * once a root is published it stays pinned until an explicit change. A missing
 * explicitly selected volume is reported, never silently replaced with an
 * empty library (D-40).
 */
sealed interface OfflineRootSelection {

    data object Auto : OfflineRootSelection

    data class Volume(val volumeId: String) : OfflineRootSelection

    fun storedValue(): String = when (this) {
        Auto -> STORED_AUTO
        is Volume -> volumeId
    }

    companion object {
        const val STORED_AUTO = "auto"

        fun fromStored(raw: String?): OfflineRootSelection =
            if (raw.isNullOrBlank() || raw == STORED_AUTO) Auto else Volume(raw)
    }
}

/**
 * One app-private directory the download store may live in. [volumeId] is the
 * stable identity persisted across restarts; [directory] is where
 * `OfflineMedias/` is created.
 */
data class OfflineRoot(
    val volumeId: String,
    val directory: File,
) {
    companion object {
        const val INTERNAL_VOLUME = "internal"
    }
}

/**
 * Published root plus the journal of an in-flight root change. The filesystem
 * is the per-file journal (every copy is verified before the root is
 * published), so these fields only need to identify what was in progress when
 * the process died (D-40).
 */
data class DownloadRootState(
    val selection: OfflineRootSelection = OfflineRootSelection.Auto,
    val activeVolumeId: String? = null,
    val generation: Long = 0,
    val pendingVolumeId: String? = null,
    val pendingSelection: OfflineRootSelection? = null,
    val pendingGeneration: Long = 0,
    val cleanupVolumeId: String? = null,
)
