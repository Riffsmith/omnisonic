package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.data.security.SecretPayload
import app.omnisonic.provider.api.ProviderAuthMethod
import app.omnisonic.provider.api.ProviderEndpoint
import app.omnisonic.provider.api.requireRemote
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CLIENT_KEY_PASSWORD = "mapper-test"

private val testKey: SecretKey by lazy {
    KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
}

class ProviderConfigMapperTest {

    private val codec = AesGcmCredentialCodec(
        keySource = SecretKeySource { testKey },
        moshi = OmnisonicJson.moshi,
    )

    private val mapper = ProviderConfigMapper(codec)

    private val json = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun encode(value: Any): String = json.adapter<Any>(value.javaClass).toJson(value)

    private fun row(
        kind: String = "REMOTE_SERVER",
        scheme: String = "https",
        host: String = "navidrome.local",
        port: Int = 4_433,
        path: String = "",
        secondary: String? = "https://backup.web:8443",
        useSecondary: Boolean = false,
        lastWorkingBlock: Int? = null,
        username: String = "admin",
        secret: SecretPayload = SecretPayload(secretValues = mapOf("password" to "hunter2")),
        authParametersJson: String? = null,
        optionsJson: String? = null,
        customHeadersJson: String? = null,
        api: String = "subsonic",
        wifiOnly: Boolean = false,
        mtlsEnabled: Boolean = false,
        mtlsKeystoreBlob: String? = null,
        mtlsAlias: String? = null,
        trustedCertsBlob: String? = null,
    ): ProviderEntity = ProviderEntity(
        id = 42L,
        name = "home",
        api = api,
        kind = kind,
        primaryScheme = scheme,
        primaryHost = host,
        primaryPort = port,
        primaryPath = path,
        secondaryScheme = secondary?.substringBefore("//")?.trimEnd(':') ?: secondary?.substringBefore(":"),
        secondaryHost = secondary?.substringAfter("//")?.substringBefore(":"),
        secondaryPort = secondary?.substringAfterLast(":")?.toIntOrNull(),
        secondaryPath = null,
        useSecondary = useSecondary,
        lastWorkingBlock = lastWorkingBlock,
        wifiOnly = wifiOnly,
        mtlsEnabled = mtlsEnabled,
        mtlsKeystoreBlob = mtlsKeystoreBlob,
        mtlsAlias = mtlsAlias,
        trustedCertsBlob = trustedCertsBlob,
        username = username,
        secretBlob = codec.seal(secret),
        authParametersJson = authParametersJson,
        optionsJson = optionsJson,
        customHeadersJson = customHeadersJson,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun `maps primary block and auth`() {
        val connection = mapper.from(row())!!

        assertEquals(42L, connection.providerId)
        assertEquals("home", connection.displayName)
        val remote = connection.requireRemote()
        assertEquals("https", remote.primary.scheme)
        assertEquals("navidrome.local", remote.primary.host)
        assertEquals(4_433, remote.primary.port)
        assertEquals("https://navidrome.local:4433", remote.primary.baseUrl())
        assertEquals(ProviderAuthMethod.TOKEN, connection.authMethod)
        assertEquals("admin", connection.username)
        assertEquals("hunter2", connection.password)
        assertNull(remote.secondary)
        assertNull(connection.lastWorkingBlock)
    }

    @Test
    fun `origin path is kept under the base url`() {
        val connection = mapper.from(row(path = "music"))!!

        assertEquals("https://navidrome.local:4433/music", connection.requireRemote().primary.baseUrl())
    }

    @Test
    fun `secondary block only enters when enabled`() {
        val skipped = mapper.from(row(secondary = "https://backup.web:8443", useSecondary = false))!!
        assertThat(skipped.requireRemote().secondary).isNull()

        val enabled = mapper.from(row(secondary = "https://backup.web:8443", useSecondary = true))!!
        val remote = enabled.requireRemote()
        assertThat(remote.secondary).isNotNull()
        assertThat(remote.secondary!!.baseUrl()).isEqualTo("https://backup.web:8443")
    }

    @Test
    fun `remembered working block survives mapping`() {
        val connection = mapper.from(row(lastWorkingBlock = 1))!!

        assertEquals(1, connection.lastWorkingBlock)
    }

    @Test
    fun `legacy auth mode is honoured`() {
        val auth = encode(AuthParametersJson(authMode = "legacy"))
        val connection = mapper.from(row(authParametersJson = auth))!!

        assertEquals(ProviderAuthMethod.LEGACY_PASSWORD, connection.authMethod)
    }

    @Test
    fun `api key secret switches to api key auth without explicit mode`() {
        val connection = mapper.from(
            row(secret = SecretPayload(secretValues = mapOf("apiKey" to "k-1"))),
        )!!

        assertEquals(ProviderAuthMethod.API_KEY, connection.authMethod)
        assertNull(connection.password)
        assertEquals("k-1", connection.apiKey)
    }

    @Test
    fun `custom headers and software hint decode from json`() {
        val options = encode(OptionsJson(serverSoftwareHint = "navidrome"))
        val headers = json.adapter<Map<String, String>>(
            Types.newParameterizedTypeWithOwner(null, Map::class.java, String::class.java, String::class.java),
        ).toJson(mapOf("X-Proxy-Id" to "omnisonic-scan"))
        val connection = mapper.from(row(optionsJson = options, customHeadersJson = headers))!!

        assertEquals("navidrome", connection.serverSoftwareHint)
        assertEquals(mapOf("X-Proxy-Id" to "omnisonic-scan"), connection.customHeaders)
    }

    @Test
    fun `malformed option json silently ignores`() {
        val connection = mapper.from(row(optionsJson = "not json", customHeadersJson = "not json"))!!

        assertNull(connection.serverSoftwareHint)
        assertEquals(emptyMap<String, String>(), connection.customHeaders)
    }

    @Test
    fun `invalid urls or port make the row unloadable`() {
        assertNull(mapper.from(row(host = "", port = 4_433, )))
        assertNull(mapper.from(row(port = 0)))
        assertNull(mapper.from(row(port = 70_000)))
        assertNull(mapper.from(row(scheme = "gopher")))
    }

    @Test
    fun `device kind rows load with a serverless endpoint`() {
        val connection = mapper.from(row(kind = "DEVICE", scheme = "local", host = "", port = 0))!!

        assertEquals(ProviderEndpoint.None, connection.endpoint)
    }

    @Test
    fun `virtual kind rows load with a serverless endpoint`() {
        val connection = mapper.from(row(kind = "VIRTUAL", scheme = "", host = "", port = 0))!!

        assertEquals(ProviderEndpoint.None, connection.endpoint)
    }

    @Test
    fun `device kind maps to none even when primary columns hold a server`() {
        val connection = mapper.from(row(kind = "DEVICE"))!!

        assertEquals(ProviderEndpoint.None, connection.endpoint)
    }

    @Test
    fun `disabled mtls ignores stored keystore and alias data`() {
        val connection = mapper.from(
            row(
                mtlsKeystoreBlob = codec.sealBytes(clientKeystoreBytes()),
                mtlsAlias = "omnisonic-client",
            ),
        )!!

        assertNull(connection.clientCertificate)
        assertEquals(emptyList<X509Certificate>(), connection.trustedServerCertificates)
    }

    @Test
    fun `enabled mtls builds the client certificate with unsealed keystore`() {
        val keystoreBytes = clientKeystoreBytes()
        val connection = mapper.from(
            row(
                mtlsEnabled = true,
                mtlsKeystoreBlob = codec.sealBytes(keystoreBytes),
                mtlsAlias = "omnisonic-client",
                secret = SecretPayload(
                    secretValues = mapOf(
                        "password" to "hunter2",
                        "mtlsKeystorePassword" to CLIENT_KEY_PASSWORD,
                    ),
                ),
            ),
        )!!

        val certificate = connection.clientCertificate!!
        assertTrue(certificate.keystorePkcs12.contentEquals(keystoreBytes))
        assertEquals(CLIENT_KEY_PASSWORD, certificate.keystorePassword)
        assertEquals("omnisonic-client", certificate.alias)
    }

    @Test
    fun `enabled mtls without a keystore blob leaves the row unloadable`() {
        assertNull(mapper.from(row(mtlsEnabled = true)))
    }

    @Test
    fun `enabled mtls with an unsealable blob leaves the row unloadable`() {
        val otherCodec = AesGcmCredentialCodec(
            keySource = SecretKeySource { KeyGenerator.getInstance("AES").apply { init(128) }.generateKey() },
        )

        assertNull(
            mapper.from(
                row(
                    mtlsEnabled = true,
                    mtlsKeystoreBlob = otherCodec.sealBytes(clientKeystoreBytes()),
                ),
            ),
        )
    }

    @Test
    fun `blank alias stays null in the client certificate`() {
        val connection = mapper.from(
            row(
                mtlsEnabled = true,
                mtlsKeystoreBlob = codec.sealBytes(clientKeystoreBytes()),
                mtlsAlias = "  ",
            ),
        )!!

        assertNull(connection.clientCertificate!!.alias)
    }

    @Test
    fun `sealed trust anchors decode into server certificates`() {
        val serverCertificate = HeldCertificate.Builder().build()
        val blob = codec.sealBytes("""["${base64Der(serverCertificate)}"]""".toByteArray())
        val connection = mapper.from(row(trustedCertsBlob = blob))!!

        assertThat(connection.trustedServerCertificates)
            .containsExactly(serverCertificate.certificate)
        assertNull(connection.clientCertificate)
    }

    @Test
    fun `unparseable trust anchors leave the row unloadable`() {
        assertNull(
            mapper.from(
                row(trustedCertsBlob = codec.sealBytes("not a cert list".toByteArray())),
            ),
        )
    }

    @Test
    fun `missing trust anchors keep ordinary server verification`() {
        val connection = mapper.from(row())!!

        assertThat(connection.trustedServerCertificates).isEmpty()
    }

    private fun clientKeystoreBytes(): ByteArray {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("omnisonic-client")
            .build()
        val keystore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keystore.setKeyEntry(
            "omnisonic-client",
            certificate.keyPair.private,
            CLIENT_KEY_PASSWORD.toCharArray(),
            arrayOf(certificate.certificate),
        )
        return ByteArrayOutputStream().use { output ->
            keystore.store(output, CLIENT_KEY_PASSWORD.toCharArray())
            output.toByteArray()
        }
    }

    private fun base64Der(certificate: HeldCertificate): String =
        Base64.getEncoder().encodeToString(certificate.certificate.encoded)
}
