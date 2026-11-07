package app.omnisonic.provider.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import okhttp3.OkHttpClient

private class TagProviderFactory(private val tag: String) : MediaProviderFactory {
    override fun apiType(): String = tag

    override fun create(connection: ProviderConnection, apiClient: OkHttpClient): MediaProvider = RecordingProvider()
}

class ProviderRegistryTest {

    @Test
    fun `resolves factory by api type string`() {
        val registry = ProviderRegistry(listOf(TagProviderFactory("subsonic"), TagProviderFactory("local")))

        assertEquals("local", registry.factoryFor("local")?.apiType())
    }

    @Test
    fun `unknown api type resolves nothing`() {
        val registry = ProviderRegistry(listOf(TagProviderFactory("subsonic")))

        assertNull(registry.factoryFor("navidrome-other"))
    }

    @Test
    fun `duplicate api type keys keep the later registration`() {
        val first = TagProviderFactory("subsonic")
        val second = TagProviderFactory("subsonic")

        val registry = ProviderRegistry(listOf(first, second))

        assertEquals(second, registry.factoryFor("subsonic"))
    }

    @Test
    fun `create delegates to the matched factory`() {
        val registry = ProviderRegistry(listOf(TagProviderFactory("subsonic")))
        val connection = ProviderConnection(
            providerId = 3L,
            displayName = "home",
            endpoint = ProviderEndpoint.Remote(ConnectionBlock("https", "example.test", 443)),
        )

        val provider = registry.create("subsonic", connection, OkHttpClient())

        assertTrue(provider is MediaProvider)
    }

    @Test
    fun `traits resolve from the matched factory`() {
        val narrow = object : MediaProviderFactory {
            override fun apiType(): String = "narrow"

            override fun traits(): ProviderTraits = ProviderTraits(
                hasLibrarySync = true,
                supportsDownload = false,
                supportsServerTranscode = false,
                reportsUserData = false,
            )

            override fun create(connection: ProviderConnection, apiClient: OkHttpClient): MediaProvider = RecordingProvider()
        }
        val registry = ProviderRegistry(listOf(TagProviderFactory("subsonic"), narrow))

        assertEquals(ProviderTraits(), registry.traitsFor("subsonic"))
        assertEquals(narrow.traits(), registry.traitsFor("narrow"))
    }

    @Test
    fun `unknown api falls back to subsonic-family trait defaults`() {
        val registry = ProviderRegistry(listOf(TagProviderFactory("subsonic")))

        assertEquals(ProviderTraits(), registry.traitsFor("unregistered"))
    }
}
