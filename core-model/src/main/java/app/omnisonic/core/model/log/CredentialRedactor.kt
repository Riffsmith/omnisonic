package app.omnisonic.core.model.log

class CredentialRedactor(
    keys: Set<String> = DEFAULT_KEYS,
    queryKeys: Set<String> = DEFAULT_QUERY_KEYS,
    private val replacement: String = DEFAULT_REPLACEMENT,
) {
    private val headerScheme = Regex("""(?i)\b(authorization|proxy-authorization)\s*:\s*(?:bearer|basic|token)?\s*\S+""")

    private val keyValue = Regex(
        """(?i)("?)(${keys.longestFirstAlternation()})("?)\s*([:=])\s*("[^"]*"|'[^']*'|[^\s,;&)}\[\]]+)"""
    )

    private val inlineScheme = Regex("""(?i)\b(bearer|basic)\s+\S+""")

    private val queryValue = Regex("""(?i)([?&])(${queryKeys.longestFirstAlternation()})=[^&\s]*""")

    private val userInfo = Regex("""(https?://)([^:/\s@]+):([^@/\s]+)@""")

    fun scrub(text: String): String = text
        .replace(headerScheme) { "${it.groupValues[1]}:$replacement" }
        .replace(keyValue) { "${it.groupValues[2]}${it.groupValues[4]}$replacement" }
        .replace(inlineScheme) { "${it.groupValues[1]} $replacement" }
        .replace(queryValue) { "${it.groupValues[1]}${it.groupValues[2]}=$replacement" }
        .replace(userInfo) { "${it.groupValues[1]}${it.groupValues[2]}:$replacement@"     }

    private companion object {
        const val DEFAULT_REPLACEMENT = "[REDACTED]"

        val DEFAULT_KEYS = setOf(
            "authorization",
            "proxy-authorization",
            "auth-token",
            "authtoken",
            "access-token",
            "accesstoken",
            "refresh-token",
            "refreshtoken",
            "api-key",
            "apikey",
            "api_key",
            "x-api-key",
            "password",
            "passwd",
            "pwd",
            "secret",
            "client-secret",
            "clientsecret",
            "private-key",
            "privatekey",
            "credentials",
            "credential",
            "cookie",
            "set-cookie",
            "token",
        )

        val DEFAULT_QUERY_KEYS = setOf(
            "u",
            "p",
            "s",
            "salt",
            "token",
            "password",
            "secret",
            "apikey",
            "api_key",
        )
    }
}

private fun Set<String>.longestFirstAlternation(): String =
    sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
