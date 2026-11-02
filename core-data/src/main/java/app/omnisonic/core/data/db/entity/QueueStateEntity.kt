package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row queue transport state. Shuffle order is persisted as entry
 * indices because restoring a seed alone would lose the current anchor.
 */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val playIndex: Int = 0,
    val shuffleOrderJson: String? = null,
    val shuffleSeed: Long? = null,
    val repeatMode: String = "OFF",
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val sourceType: String? = null,
    val sourceData: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
