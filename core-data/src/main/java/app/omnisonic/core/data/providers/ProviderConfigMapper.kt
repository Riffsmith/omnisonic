package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.ProviderKind
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.CredentialCodec
import app.omnisonic.core.data.security.SecretPayload
import app.omnisonic.provider.api.BackdateScrobbles
import app.omnisonic.provider.api.ClientCertSpec
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.ProviderAuthMethod
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderEndpoint
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Outcome of loading a persisted provider row:
 * - [Loaded]: a usable connection.
 * - [CredentialsUnreadable]: the sealed secret blob exists but cannot be
 *   unsealed (device-bound key gone, corrupt data). Never surfaced as empty
 *   credentials; the caller shows re-enter-password via ProviderStatusTracker.
 * - [Unloadable]: structurally unusable row (invalid endpoint, failed mTLS or
 *   trust-anchor decode). This is the old `null`.
 */
sealed interface ProviderLoadResult {
    data class Loaded(val connection: ProviderConnection) : ProviderLoadResult
    data class CredentialsUnreadable(val providerId: Long) : ProviderLoadResult
    data object Unloadable : ProviderLoadResult
}

class ProviderConfigMapper(
    private val codec: CredentialCodec,
    moshi: Moshi = OmnisonicJson.moshi,
) {
    private val headersAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedTypeWithOwner(null, Map::class.java, String::class.java, String::class.java),
    )

    private val optionsAdapter = moshi.adapter(OptionsJson::class.java)

    private val certListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java),
    )

    private val certificateFactory = CertificateFactory.getInstance("X.509")

    fun from(row: ProviderEntity): ProviderLoadResult {
        val endpoint = endpointFor(row) ?: return ProviderLoadResult.Unloadable
        val secrets = when {
            row.secretBlob == null -> SecretPayload()
            else -> codec.unseal(row.secretBlob) ?: return ProviderLoadResult.CredentialsUnreadable(row.id)
        }
        val options = runCatching { optionsAdapter.fromJson(row.optionsJson) }.getOrNull() ?: OptionsJson()
        val headers = runCatching { headersAdapter.fromJson(row.customHeadersJson) }.getOrNull() ?: emptyMap()
        val trustedServerCertificates = decodeTrustedCertificates(row.trustedCertsBlob)
            ?: return ProviderLoadResult.Unloadable
        val clientCertificate = when (val mtls = decodeMtls(row, secrets)) {
            MtlsConfig.Disabled -> null
            is MtlsConfig.Ready -> mtls.spec
            MtlsConfig.Invalid -> return ProviderLoadResult.Unloadable
        }

        return ProviderLoadResult.Loaded(
            ProviderConnection(
                providerId = row.id,
                displayName = row.name,
                endpoint = endpoint,
                lastWorkingBlock = row.lastWorkingBlock,
                authMethod = authMethodFor(row.authParametersJson, secrets),
                username = row.username,
                password = secrets.secretValues[SECRET_PASSWORD],
                apiKey = secrets.secretValues[SECRET_API_KEY],
                customHeaders = headers.filter { it.key.isNotBlank() },
                serverSoftwareHint = options.serverSoftwareHint,
                backdateScrobbles = decodeBackdateMode(options.backdateScrobbles),
                readTimeoutMs = options.readTimeoutMs
                    ?.coerceIn(
                        ProviderConnection.MIN_READ_TIMEOUT_MS,
                        ProviderConnection.MAX_READ_TIMEOUT_MS,
                    )
                    ?: ProviderConnection.DEFAULT_READ_TIMEOUT_MS,
                clientCertificate = clientCertificate,
                trustedServerCertificates = trustedServerCertificates,
            ),
        )
    }

    /**
     * mTLS is an explicit opt-in: disabled rows ignore every stored keystore
     * blob and alias, so persisted certificate data can never enable mTLS by
     * itself. Enabled rows must carry an unsealable keystore blob or the row
     * is unloadable (fail closed; never silently connect without the client
     * certificate). The keystore password rides the sealed secret payload and
     * is never logged.
     */
    private fun decodeMtls(row: ProviderEntity, secrets: SecretPayload): MtlsConfig {
        if (!row.mtlsEnabled) return MtlsConfig.Disabled
        val keystoreBytes = codec.unsealBytes(row.mtlsKeystoreBlob)
        if (keystoreBytes == null || keystoreBytes.isEmpty()) return MtlsConfig.Invalid
        return MtlsConfig.Ready(
            ClientCertSpec(
                keystorePkcs12 = keystoreBytes,
                keystorePassword = secrets.secretValues[SECRET_MTLS_KEYSTORE_PASSWORD],
                alias = row.mtlsAlias?.takeIf { it.isNotBlank() },
            ),
        )
    }

    /**
     * Sealed trust anchors replace platform trust only when present and
     * parseable; an unreadable blob makes the row unloadable instead of
     * downgrading verification. Null blob keeps ordinary server-certificate
     * verification.
     */
    private fun decodeTrustedCertificates(blob: String?): List<X509Certificate>? {
        if (blob == null) return emptyList()
        val sealedBytes = codec.unsealBytes(blob) ?: return null
        val encoded = runCatching { certListAdapter.fromJson(String(sealedBytes, Charsets.UTF_8)) }.getOrNull()
            ?: return null
        return runCatching {
            encoded.map { der ->
                certificateFactory.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(der)))
                    as X509Certificate
            }
        }.getOrNull()
    }

    /**
     * REMOTE_SERVER keeps the strict URL validation: a row that fails it stays
     * unloadable. DEVICE and VIRTUAL catalogs have no server endpoint at all,
     * so they never depend on the primary columns.
     */
    private fun endpointFor(row: ProviderEntity): ProviderEndpoint? =
        when (ProviderKind.fromName(row.kind)) {
            ProviderKind.REMOTE_SERVER -> {
                val primary = row.primaryBlock() ?: return null
                val secondary = if (row.useSecondary) row.secondaryBlock() else null
                ProviderEndpoint.Remote(primary, secondary)
            }

            ProviderKind.DEVICE, ProviderKind.VIRTUAL -> ProviderEndpoint.None
        }

    private fun ProviderEntity.primaryBlock(): ConnectionBlock? =
        block(primaryScheme, primaryHost, primaryPort, primaryPath)

    private fun ProviderEntity.secondaryBlock(): ConnectionBlock? {
        val host = secondaryHost ?: return null
        val port = secondaryPort ?: return null
        return block(secondaryScheme ?: "http", host, port, secondaryPath)
    }

    private fun block(scheme: String?, host: String?, port: Int?, path: String?): ConnectionBlock? {
        if (host.isNullOrBlank()) return null
        if (port == null || port < 1 || port > 65_535) return null
        val normalizedScheme = scheme?.trim()?.lowercase()
        if (normalizedScheme != "http" && normalizedScheme != "https") return null
        return ConnectionBlock(
            scheme = normalizedScheme,
            host = host.trim(),
            port = port,
            path = path?.takeIf { it.isNotBlank() },
        )
    }

    private fun authMethodFor(authParametersJson: String?, secrets: SecretPayload): ProviderAuthMethod =
        when (decodeAuthMode(authParametersJson)) {
            AUTH_MODE_LEGACY -> ProviderAuthMethod.LEGACY_PASSWORD
            AUTH_MODE_API_KEY -> ProviderAuthMethod.API_KEY
            else -> if (secrets.secretValues.containsKey(SECRET_API_KEY)) {
                ProviderAuthMethod.API_KEY
            } else {
                ProviderAuthMethod.TOKEN
            }
        }

    private fun decodeAuthMode(authParametersJson: String?): String =
        authParametersJson
            ?.let { runCatching { parametersAdapter.fromJson(it) }.getOrNull() }
            ?.authMode
            ?: AUTH_MODE_TOKEN

    private fun decodeBackdateMode(raw: String?): BackdateScrobbles = when (raw?.trim()?.lowercase()) {
        "on" -> BackdateScrobbles.ON
        "off" -> BackdateScrobbles.OFF
        else -> BackdateScrobbles.AUTO
    }

    private val parametersAdapter = moshi.adapter(AuthParametersJson::class.java)

    private sealed interface MtlsConfig {
        data object Disabled : MtlsConfig
        data class Ready(val spec: ClientCertSpec) : MtlsConfig
        data object Invalid : MtlsConfig
    }

    companion object {
        const val SECRET_PASSWORD = "password"
        const val SECRET_API_KEY = "apiKey"
        const val SECRET_MTLS_KEYSTORE_PASSWORD = "mtlsKeystorePassword"
        const val AUTH_MODE_TOKEN = "token"
        const val AUTH_MODE_LEGACY = "legacy"
        const val AUTH_MODE_API_KEY = "apiKey"
    }
}

@JsonClass(generateAdapter = true)
data class AuthParametersJson(val authMode: String? = null)

@JsonClass(generateAdapter = true)
data class OptionsJson(
    val serverSoftwareHint: String? = null,
    val backdateScrobbles: String? = null,
    val readTimeoutMs: Int? = null,
)
