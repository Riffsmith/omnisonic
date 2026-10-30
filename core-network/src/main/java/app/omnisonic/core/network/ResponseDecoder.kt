package app.omnisonic.core.network

import com.github.luben.zstd.ZstdInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

fun interface ResponseDecoder {
    @Throws(IOException::class)
    fun open(encoding: String, source: InputStream): InputStream?
}

internal object ZstdGzipResponseDecoder : ResponseDecoder {
    override fun open(encoding: String, source: InputStream): InputStream? = when {
        encoding.contains("zstd", ignoreCase = true) -> ZstdInputStream(source)
        encoding.contains("gzip", ignoreCase = true) -> GZIPInputStream(source)
        else -> null
    }
}

internal class ThrowableSafeInputStream(
    private val delegate: InputStream,
) : FilterInputStream(delegate) {

    override fun read(): Int = safe { super.read() }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        safe { delegate.read(bytes, offset, length) }

    override fun skip(byteCount: Long): Long = safe { delegate.skip(byteCount) }

    override fun available(): Int = safe { delegate.available() }

    override fun close() {
        try {
            delegate.close()
        } catch (throwable: Throwable) {
            throw throwable.asIOException("Failed to close decoded response body")
        }
    }

    override fun mark(readLimit: Int) = delegate.mark(readLimit)

    override fun reset() {
        try {
            delegate.reset()
        } catch (throwable: Throwable) {
            throw throwable.asIOException("Failed to reset decoded response body")
        }
    }

    override fun markSupported(): Boolean = delegate.markSupported()

    private inline fun <T> safe(block: () -> T): T =
        try {
            block()
        } catch (throwable: Throwable) {
            throw throwable.asIOException("Failed to decode response body")
        }
}

internal fun Throwable.asIOException(message: String): IOException =
    if (this is IOException) this else IOException(message, this)
