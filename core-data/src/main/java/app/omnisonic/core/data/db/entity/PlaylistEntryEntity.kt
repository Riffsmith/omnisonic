package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists_entries",
    indices = [
        Index(value = ["playlistId", "entryOrder"]),
        Index(value = ["providerId"]),
    ],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val providerId: Long,
    val entryOrder: Int,
    val externalId: String? = null,
    val songId: Long? = null,
    val filePath: String? = null,
    val title: String,
    val displayArtist: String? = null,
    val durationMs: Long? = null,
    val imagePath: String? = null,
    val addedAt: Long,
)
