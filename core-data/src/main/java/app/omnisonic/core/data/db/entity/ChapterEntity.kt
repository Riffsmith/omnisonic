package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    indices = [
        Index(value = ["providerId"]),
        Index(value = ["songId"]),
        Index(value = ["albumId"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val albumId: Long? = null,
    val songId: Long? = null,
    val externalId: String? = null,
    val chapterIndex: Int,
    val title: String? = null,
    val positionMs: Long,
    val endPositionMs: Long? = null,
    val sourceKind: String,
    val sourcePriority: Int,
    val updatedAt: Long,
)
