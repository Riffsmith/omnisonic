package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "album_artists",
    indices = [
        Index(value = ["providerId", "albumId", "artistId", "role"], unique = true),
        Index(value = ["artistId"]),
    ],
)
data class AlbumArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val albumId: Long,
    val artistId: Long,
    val role: String = "",
)
