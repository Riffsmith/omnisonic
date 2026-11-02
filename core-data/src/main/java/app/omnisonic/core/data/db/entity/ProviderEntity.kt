package app.omnisonic.core.data.db.entity

import androidx.room.ColumnInfo
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
    /** Name of [ProviderKind]; the default keeps pre-existing remote rows loadable. */
    @ColumnInfo(defaultValue = "REMOTE_SERVER") val kind: String = ProviderKind.REMOTE_SERVER.name,
    val primaryScheme: String? = null,
    val primaryHost: String? = null,
    val primaryPort: Int? = null,
    val primaryPath: String? = null,
    val secondaryScheme: String? = null,
    val secondaryHost: String? = null,
    val secondaryPort: Int? = null,
    val secondaryPath: String? = null,
    val useSecondary: Boolean = false,
    val lastWorkingBlock: Int? = null,
    /** Restrict sync/drain/download to Wi-Fi connectivity for this provider. */
    @ColumnInfo(defaultValue = "0") val wifiOnly: Boolean = false,
    /**
     * Explicit opt-in for mutual TLS. Stored keystore/alias data never enables
     * mTLS by itself; existing rows migrate with this false.
     */
    @ColumnInfo(defaultValue = "0") val mtlsEnabled: Boolean = false,
    /** PKCS#12 keystore sealed via [app.omnisonic.core.data.security.CredentialCodec]. */
    val mtlsKeystoreBlob: String? = null,
    /** Non-secret key alias inside the keystore; may hold several identities. */
    val mtlsAlias: String? = null,
    /** Sealed JSON array of base64 DER X.509 trust anchors; null keeps platform trust. */
    val trustedCertsBlob: String? = null,
    val username: String = "",
    val secretBlob: String? = null,
    val authParametersJson: String? = null,
    val optionsJson: String? = null,
    val customHeadersJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
