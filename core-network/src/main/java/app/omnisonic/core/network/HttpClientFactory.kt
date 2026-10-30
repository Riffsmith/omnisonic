package app.omnisonic.core.network

import app.omnisonic.core.model.log.OmnisonicLogger
import com.github.luben.zstd.ZstdInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.buffer
import okio.source

class HttpClientFactory(private val logger: OmnisonicLogger) {

    fun create(spec: ConnectionSpec): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(spec.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(spec.readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(logging())
            .addInterceptor(headerInjector(spec.headers))

        if (spec.acceptZstd) {
            builder.addInterceptor(acceptEncodings())
        }
        builder.addInterceptor(unwrapResponseEncodings())

        spec.clientKeyStore?.let { clientKeyStore ->
            val keyManagers = TlsContexts.keyManagers(clientKeyStore, spec.clientKeyStorePassword)
            val trustManager = TlsContexts.trustManager(spec.trustedCertificates)
            val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trustManager), SecureRandom()) }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }
        return builder.build()
    }

    private fun logging(): Interceptor {
        val logging = HttpLoggingInterceptor { message -> logger.debug(TAG, message) }
        logging.level = HttpLoggingInterceptor.Level.BASIC
        return logging
    }

    private fun headerInjector(headers: Map<String, String>): Interceptor =
        Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build())
        }

    private fun acceptEncodings(): Interceptor =
        Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header(ACCEPT_ENCODING_HEADER, ACCEPTED_ENCODINGS).build())
        }

    private fun unwrapResponseEncodings(): Interceptor =
        Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val encoding = response.header("Content-Encoding")
            val body = response.body
            if (body == null || encoding == null) return@Interceptor response
            val decodingStream: InputStream = when {
                encoding.contains("zstd", ignoreCase = true) -> ZstdInputStream(body.byteStream())
                encoding.contains("gzip", ignoreCase = true) -> GZIPInputStream(body.byteStream())
                else -> return@Interceptor response
            }
            response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(ResponseBody.create(body.contentType(), -1L, decodingStream.source().buffer()))
                .build()
        }

    companion object {
        private const val TAG = "HttpClient"
        private const val ACCEPT_ENCODING_HEADER = "Accept-Encoding"
        private const val ACCEPTED_ENCODINGS = "zstd, gzip"
    }
}
