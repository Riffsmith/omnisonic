package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_status",
    indices = [
        Index(value = ["providerId"], unique = true),
    ],
)
data class SyncStatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val lastFullSyncAt: Long? = null,
    val lastDiffSyncAt: Long? = null,
    val lastSyncResult: Int = 0,
    val lastSyncMessage: String? = null,
    val lastSyncCount: Int = 0,
    val lastSyncDurationMs: Long? = null,
)
