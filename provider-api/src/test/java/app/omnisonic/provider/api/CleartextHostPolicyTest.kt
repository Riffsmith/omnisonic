package app.omnisonic.provider.api

import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

class CleartextHostPolicyTest {

    @Test
    fun `private IPv4 ranges are private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("192.168.1.10")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("10.0.0.5")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("172.16.0.1")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("172.31.255.254")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("127.0.0.1")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("169.254.1.1")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("100.64.0.1")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("100.127.255.254")).isTrue()
    }

    @Test
    fun `public and edge IPv4 addresses are not private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("172.32.0.1")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("100.128.0.1")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("100.63.255.255")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("192.169.0.1")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("8.8.8.8")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("11.0.0.1")).isFalse()
    }

    @Test
    fun `malformed IPv4 is not private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("192.168.1")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("192.168.1.256")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("10.0.0.x")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("10.0.0.1.5")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("010.0.0.1")).isFalse()
    }

    @Test
    fun `localhost and dot-local names are private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("localhost")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("music.localhost")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("navidrome.local")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("LOCALHOST")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("nas.Local.")).isTrue()
    }

    @Test
    fun `similar but distinct hostnames are not private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("localhost.example.com")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("mylocalhost")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("xlocal")).isFalse()
    }

    @Test
    fun `IPv6 private ranges are private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("::1")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("fd00::1234")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("fc00::")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("fe80::1")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("[fe80::1]")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("febf::1")).isTrue()
    }

    @Test
    fun `IPv6 global and near-range addresses are not private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("2001:db8::1")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("::ffff:8.8.8.8")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("fec0::1")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("fd")).isFalse()
    }

    @Test
    fun `IPv4-mapped IPv6 follows the embedded IPv4`() {
        assertThat(CleartextHostPolicy.isPrivateHost("::ffff:192.168.1.10")).isTrue()
        assertThat(CleartextHostPolicy.isPrivateHost("::ffff:10.1.2.3")).isTrue()
    }

    @Test
    fun `empty and blank hosts are not private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("   ")).isFalse()
    }

    @Test
    fun `regular hostnames are not private`() {
        assertThat(CleartextHostPolicy.isPrivateHost("music.example.com")).isFalse()
        assertThat(CleartextHostPolicy.isPrivateHost("server-1")).isFalse()
    }
}
