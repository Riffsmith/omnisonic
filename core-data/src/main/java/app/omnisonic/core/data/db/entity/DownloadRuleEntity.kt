package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One automatic download rule, owned by a provider. Identity is
 * `(providerId, kind, targetExternalId)` with no foreign key to the catalog,
 * so deleting or recreating a playlist/album row never renames or drops the
 * rule. `kind` is persisted by NAME. FAVORITES has no external target and uses
 * the empty-string sentinel: SQLite treats NULLs as distinct in a unique index,
 * which would otherwise allow duplicate favorites rules for one provider.
 */
@Entity(
    tableName = "download_rules",
    indices = [
        Index(value = ["providerId", "kind", "targetExternalId"], unique = true),
    ],
)
data class DownloadRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val kind: String,
    val targetExternalId: String,
    val transcodeBitrateKbps: Int? = null,
    val format: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
