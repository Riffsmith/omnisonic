package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One immutable play/skip observation. [dayStartMs] is the local start of the
 * day containing [playedAtMs]; the rollup view groups on it so export and
 * per-day queries keep the old local-midnight bucket while every play keeps
 * its own timestamp.
 */
@Entity(
    tableName = "play_events",
    indices = [
        Index(value = ["providerId", "externalId", "playedAtMs"]),
        Index(value = ["providerId", "dayStartMs"]),
    ],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String,
    val playedAtMs: Long,
    val dayStartMs: Long,
    val played: Boolean,
    val skipped: Boolean = false,
    val durationMs: Long = 0,
)
