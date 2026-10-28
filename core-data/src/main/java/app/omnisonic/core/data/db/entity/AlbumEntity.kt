package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["providerId", "externalId"], unique = true),
        Index(value = ["providerId", "playCount", "lastPlayed"]),
        Index(value = ["providerId", "sortTitle"]),
        Index(value = ["providerId", "isAudiobook"]),
    ],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String? = null,
    val externalDataJson: String? = null,
    val title: String,
    val titleRoman: String? = null,
    val sortTitle: String? = null,
    val displayArtist: String? = null,
    val year: Int? = null,
    val isCompilation: Boolean = false,
    val imagePath: String? = null,
    val description: String? = null,
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    val userRating: Int? = null,
    val isFavorite: Boolean = false,
    val favoriteAt: Long? = null,
    val serverRating: Double? = null,
    val resumeFilePath: String? = null,
    val durationMs: Long? = null,
    val songCount: Int = 0,
    val isAudiobook: Boolean = false,
    val discTitlesJson: String? = null,
    val genresJson: String? = null,
    val updatedAt: Long,
)
