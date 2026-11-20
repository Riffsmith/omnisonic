package app.omnisonic.feature.downloads.service

import kotlin.math.roundToInt

/** Why the loop currently refuses to claim new rows; persisted rows stay `PENDING`. */
enum class DownloadRunBlock {
    WIFI_REQUIRED,
    STORAGE_UNAVAILABLE,
    STORAGE_LOW_SPACE,
}

/**
 * Aggregate notification inputs. [progressPercent] is an average of known
 * worker percentages, clamped to 0–100; [indeterminate] covers the `-1`
 * length-unknown marker, a blocked gate and preparation before the first claim.
 */
data class DownloadNotificationState(
    val activeCount: Int = 0,
    val pendingCount: Int = 0,
    val indeterminate: Boolean = true,
    val progressPercent: Int = 0,
    val paused: Boolean = false,
    val blocked: DownloadRunBlock? = null,
)

object DownloadNotificationRules {

    fun state(
        progressPercents: List<Int>,
        pendingCount: Int,
        paused: Boolean,
        blocked: DownloadRunBlock?,
    ): DownloadNotificationState {
        val normalized = progressPercents.map { percent ->
            if (percent < 0) -1 else percent.coerceIn(0, 100)
        }
        val known = normalized.filter { it >= 0 }
        val pending = pendingCount.coerceAtLeast(0)
        val percent = if (known.isEmpty()) 0 else known.average().roundToInt().coerceIn(0, 100)
        val indeterminate = blocked != null ||
            normalized.any { it < 0 } ||
            (normalized.isEmpty() && pending > 0)
        return DownloadNotificationState(
            activeCount = normalized.size,
            pendingCount = pending,
            indeterminate = indeterminate,
            progressPercent = percent,
            paused = paused,
            blocked = blocked,
        )
    }
}
