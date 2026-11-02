package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted queue row. Every display field is a snapshot so restoring never
 * depends on a synced `songs` row; [songRowId] is an optional convenience.
 */
@Entity(
    tableName = "queue_entries",
    indices = [
        Index(value = ["queueItemId"], unique = true),
        Index(value = ["position"], unique = true),
        Index(value = ["providerId"]),
        Index(value = ["providerId", "externalId"]),
    ],
)
data class QueueEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queueItemId: Long,
    val position: Int,
    val providerId: Long,
    val externalId: String,
    val songRowId: Long? = null,
    val title: String,
    val artistName: String? = null,
    val albumExternalId: String? = null,
    val coverImageId: String? = null,
    val durationMs: Long? = null,
    val isRadioStation: Boolean = false,
)
