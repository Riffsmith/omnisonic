package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_items",
    indices = [
        Index(value = ["mediaKey"], unique = true),
        Index(value = ["status"]),
    ],
)
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaKey: String,
    val settingsJson: String? = null,
    val status: String,
    val title: String? = null,
    val imagePath: String? = null,
    val progressPercent: Int = 0,
    val errorCount: Int = 0,
    val lastError: String? = null,
    val transcodeBitrateKbps: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
