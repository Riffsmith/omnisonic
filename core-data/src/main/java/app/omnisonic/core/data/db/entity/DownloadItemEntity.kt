package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One download task. `mediaKey` dedupes downloads and uses the stream cache
 * key vocabulary `stream:<providerId>:<externalId>:<maxBitRateKbps|auto>:<format|auto>`
 * (same format as the playback cache key), so the owning provider is
 * recoverable from the key itself; see [DownloadKeys].
 */
@Entity(
    tableName = "download_items",
    indices = [
        Index(value = ["mediaKey"], unique = true),
        Index(value = ["status"]),
        Index(value = ["providerId"]),
    ],
)
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaKey: String,
    /** Owning provider; NOT NULL in the final (v2) schema, no default owner is invented. */
    val providerId: Long,
    val settingsJson: String? = null,
    val status: String,
    val title: String? = null,
    val imagePath: String? = null,
    val progressPercent: Int = 0,
    val errorCount: Int = 0,
    val lastError: String? = null,
    val transcodeBitrateKbps: Int? = null,
    /** Keep the finished file in the transient playback cache instead of a durable copy. */
    val tempCache: Boolean = false,
    /** Durable export destination (e.g. `MEDIASTORE_DOWNLOADS`); null = app-private storage. */
    val exportTarget: String? = null,
    /** Auto-offline rule that requested this download; null = user-requested. */
    val autoRuleId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
