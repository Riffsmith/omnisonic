package app.omnisonic.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.squareup.moshi.Moshi
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import app.omnisonic.core.data.json.OmnisonicJson

data class SecretPayload(val secretValues: Map<String, String> = emptyMap())

interface CredentialCodec {
    fun seal(payload: SecretPayload): String
    fun unseal(blob: String?): SecretPayload?
}

fun interface SecretKeySource {
    fun active(): SecretKey
}

class AesGcmCredentialCodec(
    private val moshi: Moshi = OmnisonicJson.moshi,
    private val keySource: SecretKeySource,
) : CredentialCodec {

    private val payloadAdapter = moshi.adapter(SecretPayload::class.java)

    override fun seal(payload: SecretPayload): String {
        val clearText = payloadAdapter.toJson(payload).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySource.active())
        val iv = cipher.iv ?: error("AES-GCM cipher produced no IV")
        require(iv.size == IV_LENGTH_BYTES) { "unexpected IV length ${iv.size}" }
        val cipherText = cipher.doFinal(clearText)
        val sealed = ByteArray(1 + iv.size + cipherText.size)
        sealed[0] = FORMAT_VERSION
        iv.copyInto(sealed, 1)
        cipherText.copyInto(sealed, 1 + iv.size)
        return Base64.getEncoder().encodeToString(sealed)
    }

    override fun unseal(blob: String?): SecretPayload? {
        val sealed = blob
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return null
        return runCatching {
            if (sealed.size <= 1 + IV_LENGTH_BYTES || sealed[0].toInt() != FORMAT_VERSION.toInt()) return@runCatching null
            val iv = sealed.copyOfRange(1, 1 + IV_LENGTH_BYTES)
            val cipherText = sealed.copyOfRange(1 + IV_LENGTH_BYTES, sealed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySource.active(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val clearText = cipher.doFinal(cipherText)
            payloadAdapter.fromJson(String(clearText, Charsets.UTF_8))
        }.getOrNull()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1.toByte()
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}

class AndroidKeystoreKeySource(private val alias: String = KEY_ALIAS) : SecretKeySource {

    private val lazyKey = lazy { loadOrCreate() }

    override fun active(): SecretKey = lazyKey.value

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance("AES", KEYSTORE_TYPE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "omnisonic.credentials.v1"
        private const val KEYSTORE_TYPE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}
