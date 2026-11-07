package app.omnisonic.provider.api

import java.security.cert.X509Certificate

enum class ProviderAuthMethod { TOKEN, LEGACY_PASSWORD, API_KEY }

/**
 * One origin block; a provider persists two of these (primary and secondary)
 * in its endpoint columns for multi-URL failover.
 */
data class ConnectionBlock(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String? = null,
) {
    /** Base URL including an optional origin path, without trailing slash. */
    fun baseUrl(): String {
        val suffix = path?.trim()?.trim('/')?.let { "/$it" } ?: ""
        return "$scheme://$host:$port$suffix"
    }
}

/**
 * Where a provider's API and media live. Server kinds carry one required
 * primary block plus an optional failover block; device and virtual catalogs
 * have no server at all. Sealed so server-only clients fail at one named seam
 * ([requireRemote]) instead of scattering nullable-block guards, and so
 * serverless rows are representable without placeholder host/port values.
 */
sealed interface ProviderEndpoint {
    data class Remote(
        val primary: ConnectionBlock,
        val secondary: ConnectionBlock? = null,
    ) : ProviderEndpoint

    data object None : ProviderEndpoint
}

val ProviderConnection.remoteEndpoint: ProviderEndpoint.Remote?
    get() = endpoint as? ProviderEndpoint.Remote

/** Server blocks for server-only clients; fails fast on serverless endpoints. */
fun ProviderConnection.requireRemote(): ProviderEndpoint.Remote =
    remoteEndpoint ?: error("provider $providerId has no remote endpoint")

/** Immutable view of one provider row for the client layer; secrets already unsealed. */
data class ProviderConnection(
    val providerId: Long,
    val displayName: String = "",
    val endpoint: ProviderEndpoint = ProviderEndpoint.None,
    val lastWorkingBlock: Int? = null,
    val authMethod: ProviderAuthMethod = ProviderAuthMethod.TOKEN,
    val username: String = "",
    val password: String? = null,
    val apiKey: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val serverSoftwareHint: String? = null,
    val backdateScrobbles: BackdateScrobbles = BackdateScrobbles.AUTO,
    /**
     * Client identity for mutual TLS; null whenever the provider has mTLS
     * disabled (stored keystore data never enables mTLS by itself).
     */
    val clientCertificate: ClientCertSpec? = null,
    /**
     * Explicit server trust anchors replacing platform trust when non-empty;
     * empty keeps ordinary server-certificate verification.
     */
    val trustedServerCertificates: List<X509Certificate> = emptyList(),
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    companion object {
        const val PRIMARY_BLOCK = 0
        const val SECONDARY_BLOCK = 1
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
        const val MIN_READ_TIMEOUT_MS = 5_000
        const val MAX_READ_TIMEOUT_MS = 120_000
    }
}
