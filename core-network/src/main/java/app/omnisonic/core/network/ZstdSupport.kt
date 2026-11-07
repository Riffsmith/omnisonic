package app.omnisonic.core.network

import com.github.luben.zstd.Zstd

/** Reports whether zstd response bodies can be decoded on this device. */
fun interface ZstdProbe {
    fun isAvailable(): Boolean
}

/**
 * One-shot probe: a full compress/decompress round trip is the only reliable
 * check, because a missing or broken native library surfaces as an Error
 * (UnsatisfiedLinkError, ExceptionInInitializerError, NoClassDefFoundError)
 * on first use, not as a queryable flag.
 */
internal object ZstdSupport : ZstdProbe {

    val available: Boolean by lazy { isAvailable() }

    override fun isAvailable(): Boolean = try {
        val payload = PROBE_PAYLOAD.toByteArray()
        val compressed = Zstd.compress(payload)
        Zstd.decompress(compressed, payload.size).contentEquals(payload)
    } catch (throwable: Throwable) {
        false
    }

    private const val PROBE_PAYLOAD = "omnisonic-zstd-probe"
}
