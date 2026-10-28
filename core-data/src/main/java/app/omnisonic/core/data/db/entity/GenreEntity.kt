package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "genres",
    indices = [
        Index(value = ["providerId", "externalId"], unique = true),
        Index(value = ["providerId"]),
    ],
)
data class GenreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String? = null,
    val externalDataJson: String? = null,
    val title: String,
    val sortTitle: String? = null,
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val playCount: Int = 0,
    val updatedAt: Long,
)
