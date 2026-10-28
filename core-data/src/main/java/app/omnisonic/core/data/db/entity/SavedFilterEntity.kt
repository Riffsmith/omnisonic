package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_filters",
    indices = [
        Index(value = ["mediaType", "name"], unique = true),
    ],
)
data class SavedFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mediaType: String,
    val filterJson: String,
    val updatedAt: Long,
)
