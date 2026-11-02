package app.omnisonic.provider.api

/**
 * Why a provider may (not) run sync, outbox drain, or download work right now.
 * [OFFLINE] and [NEEDS_WIFI] are network facts; [AUTH_FAILED] is reserved for
 * unreadable credentials (re-enter password) and is wired by the credentials
 * layer. [OK] means the run gate allows the work.
 */
enum class ProviderStatus { OK, NEEDS_WIFI, AUTH_FAILED, OFFLINE }

/**
 * Single seam for "may this provider run now?"; sync, outbox drain and
 * download enqueue consult it so network checks are not duplicated per path.
 * Any status other than [ProviderStatus.OK] blocks the work.
 */
fun interface ProviderRunGate {
    suspend fun check(providerId: Long): ProviderStatus
}

/** Permissive gate for call sites that carry no tracker. */
val AllowAllRunGate: ProviderRunGate = ProviderRunGate { ProviderStatus.OK }

/**
 * Client certificate identity for mutual TLS, decoded from the sealed
 * provider row by the config mapper. [keystorePkcs12] is an unsealed PKCS#12
 * blob; [keystorePassword] is never logged; [alias] selects the entry when
 * the keystore holds several identities. Equality is bytewise on purpose so
 * accidental secret exposure through generated toString/hashCode stays out.
 */
class ClientCertSpec(
    val keystorePkcs12: ByteArray,
    val keystorePassword: String?,
    val alias: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is ClientCertSpec &&
            keystorePkcs12.contentEquals(other.keystorePkcs12) &&
            keystorePassword == other.keystorePassword &&
            alias == other.alias

    override fun hashCode(): Int {
        var result = keystorePkcs12.contentHashCode()
        result = 31 * result + keystorePassword.hashCode()
        result = 31 * result + alias.hashCode()
        return result
    }
}
