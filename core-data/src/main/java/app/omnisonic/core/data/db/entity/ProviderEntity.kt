package app.omnisonic.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val api: String,
    val isEnabled: Boolean = true,
    val displayOrder: Int = 0,
    val primaryScheme: String,
    val primaryHost: String,
    val primaryPort: Int,
    val primaryPath: String,
    val secondaryScheme: String? = null,
    val secondaryHost: String? = null,
    val secondaryPort: Int? = null,
    val secondaryPath: String? = null,
    val useSecondary: Boolean = false,
    val lastWorkingBlock: Int? = null,
    val username: String = "",
    val secretBlob: String? = null,
    val authParametersJson: String? = null,
    val optionsJson: String? = null,
    val customHeadersJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
