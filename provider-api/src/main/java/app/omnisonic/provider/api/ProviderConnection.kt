package app.omnisonic.provider.api

enum class ProviderAuthMethod { TOKEN, LEGACY_PASSWORD, API_KEY }

/**
 * One origin block persisted in providers.primary_[url]/secondary_[url] columns
 * (see .agents/memory/decisions.md, multi-URL failover decision).
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

/** Immutable view of one provider row for the client layer; secrets already unsealed. */
data class ProviderConnection(
    val providerId: Long,
    val displayName: String = "",
    val primary: ConnectionBlock,
    val secondary: ConnectionBlock? = null,
    val lastWorkingBlock: Int? = null,
    val authMethod: ProviderAuthMethod = ProviderAuthMethod.TOKEN,
    val username: String = "",
    val password: String? = null,
    val apiKey: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val serverSoftwareHint: String? = null,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    companion object {
        const val PRIMARY_BLOCK = 0
        const val SECONDARY_BLOCK = 1
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000
    }
}
