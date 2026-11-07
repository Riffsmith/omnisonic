package app.omnisonic.provider.api

/** Base class for provider errors callers branch on, e.g. retry policy in sync/outbox. */
sealed class ProviderException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

class ProviderTransportException(cause: Throwable) :
    ProviderException("transport failure (${cause.javaClass.simpleName})", cause)

class ProviderFatalException(cause: Throwable) :
    ProviderException("fatal provider failure", cause)

class ProviderHttpError(val code: Int, val serverMessage: String? = null) :
    ProviderException("HTTP $code ${serverMessage.orEmpty()}")

/**
 * The server rejected the provider's credentials or authorization. Callers
 * branch on this type to mark the provider AUTH_FAILED and stop retrying;
 * network outages and other server errors never use it.
 */
class ProviderAuthException(message: String? = null, cause: Throwable? = null) :
    ProviderException(message ?: "provider authentication failed", cause)
