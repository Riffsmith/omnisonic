package app.omnisonic.core.network

import app.omnisonic.core.model.log.OmnisonicLogger
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Metadata-only HTTP log line, written once per completed call through the
 * injected (already redacting) logger. It never reads, buffers or closes the
 * response body, and it never logs headers or bodies: the address, length and
 * content type describe the call without capturing its payload.
 *
 * Registered as an application interceptor after `HttpClientFactory.logging()`,
 * so one line covers the whole call including redirects and retries.
 */
class NetworkLogInterceptor(
    private val logger: OmnisonicLogger,
    private val gate: NetworkLogGate = NetworkLogGate.DISABLED,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!gate.isEnabled()) return chain.proceed(chain.request())
        val request = chain.request()
        val startedAt = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            logger.info(TAG, summary(request, response, elapsedMs(startedAt)))
            response
        } catch (failure: IOException) {
            logger.warn(TAG, failureLine(request, failure, elapsedMs(startedAt)))
            throw failure
        }
    }

    private fun summary(request: Request, response: Response, elapsedMs: Long): String = buildString {
        append(request.method).append(' ').append(request.url)
        append(" -> ").append(response.code)
        append(" in ").append(elapsedMs).append(" ms")
        val body = response.body
        append(", ").append(body?.contentLength()?.takeIf { it >= 0 }?.let { "$it bytes" } ?: "unknown bytes")
        body?.contentType()?.let { append(", ").append(it) }
    }

    private fun failureLine(request: Request, failure: IOException, elapsedMs: Long): String =
        "${request.method} ${request.url} failed after $elapsedMs ms: " +
            "${failure.javaClass.name}: ${failure.message.orEmpty()}"

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private companion object {
        const val TAG = "NetworkLog"
    }
}
