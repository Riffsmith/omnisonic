package app.omnisonic.core.model.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CredentialRedactorTest {

    private val redactor = CredentialRedactor()

    @Test
    fun bearerTokenInHeaderIsRedacted() {
        assertThat(redactor.scrub("Authorization: Bearer eyJhbGciOi.eyJzdWIi.xyz"))
            .isEqualTo("Authorization:[REDACTED]")
    }

    @Test
    fun bareBearerTokenIsRedacted() {
        assertThat(redactor.scrub("sent bearer eyJhbGciOiJub25l.xyz for retry"))
            .isEqualTo("sent bearer [REDACTED] for retry")
    }

    @Test
    fun basicAuthIsRedacted() {
        assertThat(redactor.scrub("Authorization: Basic dXNlcjpwYXNz"))
            .isEqualTo("Authorization:[REDACTED]")
    }

    @Test
    fun passwordPairsAreRedacted() {
        assertThat(redactor.scrub("password=hunter2")).isEqualTo("password=[REDACTED]")
        assertThat(redactor.scrub("password: hunter2")).isEqualTo("password:[REDACTED]")
        assertThat(redactor.scrub("\"password\":\"hunter2\"")).isEqualTo("password:[REDACTED]")
    }

    @Test
    fun apiKeyHeadersAreRedacted() {
        assertThat(redactor.scrub("x-api-key: abc123")).isEqualTo("x-api-key:[REDACTED]")
    }

    @Test
    fun queryCredentialsAreRedacted() {
        val scrubbed = redactor.scrub(
            "https://host/rest/ping.view?u=admin&t=abc123&p=enc%3Aabc&salt=abc&v=1.8.0&c=omnisonic&f=json"
        )
        assertThat(scrubbed).isEqualTo(
            "https://host/rest/ping.view?u=[REDACTED]&t=[REDACTED]&p=[REDACTED]&salt=[REDACTED]&v=1.8.0&c=omnisonic&f=json"
        )
    }

    @Test
    fun nonCredentialQueryParamsAreKept() {
        assertThat(redactor.scrub("https://host/stream?maxBitRate=320&id=42"))
            .isEqualTo("https://host/stream?maxBitRate=320&id=42")
    }

    @Test
    fun urlUserInfoPasswordIsRedacted() {
        assertThat(redactor.scrub("failed to reach https://admin:hunter2@navidrome.local:4533/ping"))
            .isEqualTo("failed to reach https://admin:[REDACTED]@navidrome.local:4533/ping")
    }

    @Test
    fun ordinaryTextIsUntouched() {
        assertThat(redactor.scrub("sync finished: 1024 albums, 12345 songs in 3.2s"))
            .isEqualTo("sync finished: 1024 albums, 12345 songs in 3.2s")
    }

    @Test
    fun customReplacementIsHonored() {
        val custom = CredentialRedactor(replacement = "***")
        assertThat(custom.scrub("token=abc")).isEqualTo("token=***")
    }
}
