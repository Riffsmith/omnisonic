package app.omnisonic.provider.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class TagProviderFactory(private val tag: String) : MediaProviderFactory {
    override fun apiType(): String = tag

    override fun create(connection: ProviderConnection): MediaProvider = RecordingProvider()
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
            primary = ConnectionBlock("https", "example.test", 443),
        )

        val provider = registry.create("subsonic", connection)

        assertTrue(provider is MediaProvider)
    }
}
