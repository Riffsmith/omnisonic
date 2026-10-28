package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "song_artists",
    indices = [
        Index(value = ["providerId", "songId", "artistId", "role"], unique = true),
        Index(value = ["artistId"]),
    ],
)
data class SongArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val songId: Long,
    val artistId: Long,
    val role: String = "",
)
