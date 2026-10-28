package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "library_sources",
    indices = [
        Index(value = ["providerId"]),
        Index(value = ["providerId", "externalId"], unique = true),
    ],
)
data class LibrarySourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val externalId: String? = null,
    val externalDataJson: String? = null,
    val mediaType: String,
    val title: String? = null,
    val imagePath: String? = null,
    val pathsJson: String,
    val updatedAt: Long,
)
