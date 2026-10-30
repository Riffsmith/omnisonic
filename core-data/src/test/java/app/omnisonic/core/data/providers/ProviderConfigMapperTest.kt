package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.data.security.SecretPayload
import app.omnisonic.provider.api.ProviderAuthMethod
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.google.common.truth.Truth.assertThat
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
    ): ProviderEntity = ProviderEntity(
        id = 42L,
        name = "home",
        api = api,
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
        assertEquals("https", connection.primary.scheme)
        assertEquals("navidrome.local", connection.primary.host)
        assertEquals(4_433, connection.primary.port)
        assertEquals("https://navidrome.local:4433", connection.primary.baseUrl())
        assertEquals(ProviderAuthMethod.TOKEN, connection.authMethod)
        assertEquals("admin", connection.username)
        assertEquals("hunter2", connection.password)
        assertNull(connection.secondary)
        assertNull(connection.lastWorkingBlock)
    }

    @Test
    fun `origin path is kept under the base url`() {
        val connection = mapper.from(row(path = "music"))!!

        assertEquals("https://navidrome.local:4433/music", connection.primary.baseUrl())
    }

    @Test
    fun `secondary block only enters when enabled`() {
        val skipped = mapper.from(row(secondary = "https://backup.web:8443", useSecondary = false))!!
        assertThat(skipped.secondary).isNull()

        val enabled = mapper.from(row(secondary = "https://backup.web:8443", useSecondary = true))!!
        assertThat(enabled.secondary).isNotNull()
        assertThat(enabled.secondary!!.baseUrl()).isEqualTo("https://backup.web:8443")
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
}
