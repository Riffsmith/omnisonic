package app.omnisonic.core.data.providers

import app.omnisonic.provider.api.CleartextHostPolicy
import app.omnisonic.provider.api.ConnectionBlock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A validated server origin entered on the provider setup form. Carries the
 * persisted connection block plus the two transport facts the form needs:
 * cleartext transport and whether the host is private-range
 * (docs/omnisonic/network/cleartext-policy.md).
 */
data class ParsedServerUrl(
    val block: ConnectionBlock,
    val isCleartext: Boolean,
    val isPrivateHost: Boolean,
) {
    val displayName: String get() = block.host
}

/**
 * Parses a full `http(s)://host[:port][/path]` URL. A missing scheme is
 * rejected instead of guessed: self-hosted servers commonly run plain http,
 * so defaulting to https would produce a confusing connection failure.
 * Embedded credentials, queries and fragments are rejected so no secret can
 * ride the URL into a log or provider row.
 */
fun parseServerUrl(raw: String): ParsedServerUrl? {
    val trimmed = raw.trim()
    val schemePrefix = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https"
        trimmed.startsWith("http://", ignoreCase = true) -> "http"
        else -> return null
    }
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.query != null || url.fragment != null) return null
    val path = url.pathSegments.filter { it.isNotEmpty() }.joinToString("/").takeIf { it.isNotEmpty() }
    return ParsedServerUrl(
        block = ConnectionBlock(
            scheme = schemePrefix,
            host = url.host,
            port = url.port,
            path = path,
        ),
        isCleartext = schemePrefix == "http",
        isPrivateHost = CleartextHostPolicy.isPrivateHost(url.host),
    )
}
