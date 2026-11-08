package app.omnisonic.core.data.providers

import app.omnisonic.provider.api.ConnectionBlock
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ServerUrlTest {

    @Test
    fun `https URL without port keeps the scheme default port and no path`() {
        val parsed = parseServerUrl("https://music.example.com")!!

        assertThat(parsed.block).isEqualTo(
            ConnectionBlock(scheme = "https", host = "music.example.com", port = 443, path = null),
        )
        assertThat(parsed.isCleartext).isFalse()
        assertThat(parsed.displayName).isEqualTo("music.example.com")
    }

    @Test
    fun `http URL keeps an explicit port and an origin path prefix`() {
        val parsed = parseServerUrl("  http://192.168.1.20:4533/navidrome/  ")!!

        assertThat(parsed.block).isEqualTo(
            ConnectionBlock(scheme = "http", host = "192.168.1.20", port = 4533, path = "navidrome"),
        )
        assertThat(parsed.isCleartext).isTrue()
        assertThat(parsed.isPrivateHost).isTrue()
    }

    @Test
    fun `root path collapses to no path`() {
        val parsed = parseServerUrl("http://localhost:8080/")!!

        assertThat(parsed.block.path).isNull()
        assertThat(parsed.block.baseUrl()).isEqualTo("http://localhost:8080")
    }

    @Test
    fun `missing scheme is rejected instead of guessed`() {
        assertThat(parseServerUrl("music.example.com")).isNull()
        assertThat(parseServerUrl("192.168.1.20:4533")).isNull()
    }

    @Test
    fun `embedded credentials query and fragment are rejected`() {
        assertThat(parseServerUrl("https://user:secret@music.example.com")).isNull()
        assertThat(parseServerUrl("https://music.example.com?token=abc")).isNull()
        assertThat(parseServerUrl("https://music.example.com#fragment")).isNull()
    }

    @Test
    fun `blank and malformed URLs are rejected`() {
        assertThat(parseServerUrl("")).isNull()
        assertThat(parseServerUrl("   ")).isNull()
        assertThat(parseServerUrl("http://")).isNull()
        assertThat(parseServerUrl("ftp://music.example.com")).isNull()
    }

    @Test
    fun `cleartext to a public host is not private`() {
        val parsed = parseServerUrl("http://music.example.com")!!

        assertThat(parsed.isCleartext).isTrue()
        assertThat(parsed.isPrivateHost).isFalse()
    }

    @Test
    fun `ipv6 literal hosts get bracketed when a base URL is built`() {
        val parsed = parseServerUrl("http://[::1]:4533/music")!!

        assertThat(parsed.block.host).isEqualTo("::1")
        assertThat(parsed.block.baseUrl()).isEqualTo("http://[::1]:4533/music")
    }
}
