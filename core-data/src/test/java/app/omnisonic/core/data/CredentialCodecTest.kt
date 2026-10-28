package app.omnisonic.core.data

import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretPayload
import app.omnisonic.core.data.security.SecretKeySource
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Test

class CredentialCodecTest {

    private class FixedKeySource(private val key: SecretKey) : SecretKeySource {
        override fun active(): SecretKey = key
    }

    private fun aes256Key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripSealsThenUnseals() {
        val codec = AesGcmCredentialCodec(keySource = FixedKeySource(aes256Key()))
        val payload = SecretPayload(secretValues = mapOf("password" to "pw-1", "token" to "tok-99"))

        val sealed = codec.seal(payload)
        val decoded = codec.unseal(sealed)

        assertThat(decoded).isEqualTo(payload)
    }

    @Test
    fun sealUsesRandomIvAndVersionedBlob() {
        val key = aes256Key()
        val codec = AesGcmCredentialCodec(keySource = FixedKeySource(key))
        val payload = SecretPayload(secretValues = mapOf("password" to "pw-1"))

        val first = Base64.getDecoder().decode(codec.seal(payload))
        val second = Base64.getDecoder().decode(codec.seal(payload))

        assertThat(first[0].toInt()).isEqualTo(1)
        assertThat(second[0].toInt()).isEqualTo(1)
        assertThat(first.copyOfRange(1, 13)).isNotEqualTo(second.copyOfRange(1, 13))
        assertThat(first.size).isGreaterThan(12)
    }

    @Test
    fun unsealReturnsNullForCorruptOrForeignBlobs() {
        val codec = AesGcmCredentialCodec(keySource = FixedKeySource(aes256Key()))
        val sealed = codec.seal(SecretPayload(secretValues = mapOf("password" to "pw-1")))

        val bytes = Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0x10).toByte()
        val tampered = Base64.getEncoder().encodeToString(bytes)
        val badVersion = Base64.getEncoder().encodeToString(
            byteArrayOf(7.toByte()) + bytes.copyOfRange(1, bytes.size),
        )

        assertThat(codec.unseal(tampered)).isNull()
        assertThat(codec.unseal(badVersion)).isNull()
        assertThat(codec.unseal(null)).isNull()
        assertThat(codec.unseal("")).isNull()
        assertThat(codec.unseal("not!base64")).isNull()

        val crossTest = AesGcmCredentialCodec(keySource = FixedKeySource(aes256Key()))
        assertThat(crossTest.unseal(sealed)).isNull()
    }

    @Test
    fun emptyPayloadRoundTripsDecoding() {
        val codec = AesGcmCredentialCodec(keySource = FixedKeySource(aes256Key()))

        val decoded = codec.unseal(codec.seal(SecretPayload()))

        assertThat(decoded).isEqualTo(SecretPayload())
    }
}
