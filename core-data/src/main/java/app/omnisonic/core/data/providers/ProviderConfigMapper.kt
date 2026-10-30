package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.CredentialCodec
import app.omnisonic.core.data.security.SecretPayload
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.ProviderAuthMethod
import app.omnisonic.provider.api.ProviderConnection
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

class ProviderConfigMapper(
    private val codec: CredentialCodec,
    moshi: Moshi = OmnisonicJson.moshi,
) {
    private val headersAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedTypeWithOwner(null, Map::class.java, String::class.java, String::class.java),
    )

    private val optionsAdapter = moshi.adapter(OptionsJson::class.java)

    fun from(row: ProviderEntity): ProviderConnection? {
        val primary = row.primaryBlock() ?: return null
        val secondary = if (row.useSecondary) row.secondaryBlock() else null
        val secrets = codec.unseal(row.secretBlob) ?: SecretPayload()
        val options = runCatching { optionsAdapter.fromJson(row.optionsJson) }.getOrNull() ?: OptionsJson()
        val headers = runCatching { headersAdapter.fromJson(row.customHeadersJson) }.getOrNull() ?: emptyMap()

        return ProviderConnection(
            providerId = row.id,
            displayName = row.name,
            primary = primary,
            secondary = secondary,
            lastWorkingBlock = row.lastWorkingBlock,
            authMethod = authMethodFor(row.authParametersJson, secrets),
            username = row.username,
            password = secrets.secretValues[SECRET_PASSWORD],
            apiKey = secrets.secretValues[SECRET_API_KEY],
            customHeaders = headers.filter { it.key.isNotBlank() },
            serverSoftwareHint = options.serverSoftwareHint,
        )
    }

    private fun ProviderEntity.primaryBlock(): ConnectionBlock? =
        block(primaryScheme, primaryHost, primaryPort, primaryPath)

    private fun ProviderEntity.secondaryBlock(): ConnectionBlock? {
        val host = secondaryHost ?: return null
        val port = secondaryPort ?: return null
        return block(secondaryScheme ?: "http", host, port, secondaryPath)
    }

    private fun block(scheme: String, host: String, port: Int, path: String?): ConnectionBlock? {
        if (host.isBlank()) return null
        if (port < 1 || port > 65_535) return null
        val normalizedScheme = scheme.trim().lowercase()
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

    private val parametersAdapter = moshi.adapter(AuthParametersJson::class.java)

    companion object {
        const val SECRET_PASSWORD = "password"
        const val SECRET_API_KEY = "apiKey"
        const val AUTH_MODE_TOKEN = "token"
        const val AUTH_MODE_LEGACY = "legacy"
        const val AUTH_MODE_API_KEY = "apiKey"
    }
}

data class AuthParametersJson(val authMode: String? = null)

data class OptionsJson(val serverSoftwareHint: String? = null)
