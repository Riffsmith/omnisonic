package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbox_items",
    indices = [
        Index(value = ["providerId", "mediaType", "externalId", "action"], unique = true),
        Index(value = ["providerId", "nextAttemptAt"]),
    ],
)
data class OutboxItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val mediaType: String,
    val externalId: String,
    val action: String,
    val payloadJson: String? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long,
    val lastError: String? = null,
    val createdAt: Long,
)
