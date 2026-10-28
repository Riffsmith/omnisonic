package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_events",
    indices = [
        Index(value = ["providerId", "externalId", "date"], unique = true),
        Index(value = ["providerId", "date"]),
    ],
)
data class PlaybackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String,
    val date: Long,
    val played: Boolean,
    val skipped: Boolean = false,
    val durationMs: Long = 0,
)
