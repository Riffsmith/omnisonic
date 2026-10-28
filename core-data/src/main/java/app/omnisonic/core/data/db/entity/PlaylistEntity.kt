package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["providerId"]),
        Index(value = ["providerId", "type"]),
    ],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String? = null,
    val externalDataJson: String? = null,
    val title: String,
    val description: String? = null,
    val imagePath: String? = null,
    val type: Int,
    val smartFilterJson: String? = null,
    val isFavorite: Boolean = false,
    val favoriteAt: Long? = null,
    val autoSync: Boolean = false,
    val autoOffline: Boolean = false,
    val entryCount: Int = 0,
    val durationMs: Long? = null,
    val lastModified: Long? = null,
    val lastPlayed: Long? = null,
    val hidden: Boolean = false,
    val updatedAt: Long,
)
