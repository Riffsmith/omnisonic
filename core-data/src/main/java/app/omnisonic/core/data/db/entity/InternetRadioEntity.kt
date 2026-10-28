package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "internet_radios",
    indices = [
        Index(value = ["providerId", "externalId"], unique = true),
        Index(value = ["providerId"]),
    ],
)
data class InternetRadioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String? = null,
    val externalDataJson: String? = null,
    val title: String,
    val streamUrl: String,
    val description: String? = null,
    val imagePath: String? = null,
    val genresJson: String? = null,
    val tagsJson: String? = null,
    val isFavorite: Boolean = false,
    val favoriteAt: Long? = null,
    val lastPlayed: Long? = null,
    val updatedAt: Long,
)
