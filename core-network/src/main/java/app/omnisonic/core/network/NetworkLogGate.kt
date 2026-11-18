package app.omnisonic.core.network

/**
 * Per-call opt-in for HTTP logging. The interceptor reads it on every request
 * (never at client-build time), so toggling the setting takes effect on the
 * already-cached clients.
 */
fun interface NetworkLogGate {
    fun isEnabled(): Boolean

    companion object {
        /** Default for every client that is not wired to user settings. */
        val DISABLED: NetworkLogGate = NetworkLogGate { false }
    }
}
