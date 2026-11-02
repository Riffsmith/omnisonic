package app.omnisonic.core.data.db.entity

import androidx.room.DatabaseView

/** Per-track per-local-day rollup over [PlayEventEntity]; reads only, no storage. */
@DatabaseView(
    viewName = "play_events_daily",
    value = """
        SELECT providerId,
               externalId,
               dayStartMs AS date,
               MAX(played) AS played,
               MAX(skipped) AS skipped,
               SUM(durationMs) AS durationMs,
               COUNT(*) AS playCount
        FROM play_events
        GROUP BY providerId, externalId, dayStartMs
    """,
)
data class PlaybackDayRollup(
    val providerId: Long,
    val externalId: String,
    val date: Long,
    val played: Boolean,
    val skipped: Boolean,
    val durationMs: Long,
    val playCount: Int,
)
