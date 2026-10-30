package app.omnisonic.provider.api

/**
 * Classifies whether an HTTP host is private-range, for the cleartext-warning policy:
 * cleartext to a private host is a normal self-hosted setup; cleartext to a
 * non-private host should surface an in-app warning before the provider is saved.
 */
object CleartextHostPolicy {

    fun isPrivateHost(host: String): Boolean {
        val normalized = host.trim().lowercase().trimEnd('.')

        if (normalized.isEmpty()) return false

        val unbracketed = normalized.removePrefix("[").removeSuffix("]")

        if (unbracketed == "localhost" || unbracketed.endsWith(".localhost")) return true
        if (unbracketed.endsWith(".local")) return true

        if (isIpV4Literal(unbracketed)) return isPrivateIpV4(unbracketed)

        if (unbracketed.contains(':')) return isPrivateIpV6(unbracketed)

        return false
    }

    private fun isIpV4Literal(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        return octets.all { octet ->
            octet.isNotEmpty() && octet.length <= 3 && octet.all { it.isDigit() } &&
                octet.toIntOrNull()?.let { it in 0..255 } == true &&
                !(octet.length > 1 && octet.startsWith("0"))
        }
    }

    private fun isPrivateIpV4(host: String): Boolean {
        val octets = host.split('.').map { it.toInt() }
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    private fun isPrivateIpV6(host: String): Boolean {
        val embedded = embeddedIpV4(host)
        if (embedded != null) return isPrivateIpV4(embedded)

        if (host == "::1") return true

        val firstGroup = host.substringBefore(':').removePrefix("0x")
        val value = firstGroup.toIntOrNull(16) ?: return false

        return when {
            value >= 0xfc00 && value <= 0xfdff -> true
            value >= 0xfe80 && value <= 0xfebf -> true
            else -> false
        }
    }

    private fun embeddedIpV4(host: String): String? {
        val tail = host.substringAfterLast(':')
        return tail.takeIf { it.contains('.') && isIpV4Literal(it) }
    }
}
