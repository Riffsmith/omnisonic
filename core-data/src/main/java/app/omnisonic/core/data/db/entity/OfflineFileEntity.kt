package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_files",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index(value = ["offlinePath"], unique = true),
        Index(value = ["providerId"]),
    ],
)
data class OfflineFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val mediaType: String,
    val sourceKey: String,
    val offlinePath: String,
    val sizeBytes: Long? = null,
    val title: String? = null,
    val imagePath: String? = null,
    val bitrateKbps: Int? = null,
    val autoRule: String? = null,
    val downloadedAt: Long? = null,
    val syncedAt: Long? = null,
)
