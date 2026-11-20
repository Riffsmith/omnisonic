package app.omnisonic.core.data.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DownloadTransferRulesTest {

    @Test
    fun `progress is bounded and indeterminate without a total`() {
        assertThat(DownloadTransferRules.progressPercent(0, null)).isEqualTo(-1)
        assertThat(DownloadTransferRules.progressPercent(0, 0)).isEqualTo(-1)
        assertThat(DownloadTransferRules.progressPercent(0, 1_000)).isEqualTo(0)
        assertThat(DownloadTransferRules.progressPercent(500, 1_000)).isEqualTo(50)
        assertThat(DownloadTransferRules.progressPercent(1_000, 1_000)).isEqualTo(100)
        assertThat(DownloadTransferRules.progressPercent(1_500, 1_000)).isEqualTo(100)
        assertThat(DownloadTransferRules.progressPercent(-5, 1_000)).isEqualTo(0)
        assertThat(DownloadTransferRules.progressPercent(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(100)
    }

    @Test
    fun `response encoding must be identity`() {
        assertThat(DownloadTransferRules.isIdentityEncoding(null)).isTrue()
        assertThat(DownloadTransferRules.isIdentityEncoding("")).isTrue()
        assertThat(DownloadTransferRules.isIdentityEncoding(" IDENTITY ")).isTrue()
        assertThat(DownloadTransferRules.isIdentityEncoding("gzip")).isFalse()
        assertThat(DownloadTransferRules.isIdentityEncoding("br, gzip")).isFalse()
    }

    @Test
    fun `login and protocol payload types are rejected`() {
        assertThat(DownloadTransferRules.rejectsContentType("application/json")).isTrue()
        assertThat(DownloadTransferRules.rejectsContentType("application/problem+json; charset=utf-8")).isTrue()
        assertThat(DownloadTransferRules.rejectsContentType("text/html; charset=UTF-8")).isTrue()
        assertThat(DownloadTransferRules.rejectsContentType("application/xhtml+xml")).isTrue()
        assertThat(DownloadTransferRules.rejectsContentType("text/xml")).isTrue()
        assertThat(DownloadTransferRules.rejectsContentType("text/plain")).isTrue()
        assertThat(DownloadTransferRules.rejectsContentType("audio/mpeg")).isFalse()
        assertThat(DownloadTransferRules.rejectsContentType("application/octet-stream")).isFalse()
        assertThat(DownloadTransferRules.rejectsContentType(null)).isFalse()
    }

    @Test
    fun `text openers are rejected even without a content type`() {
        assertThat(DownloadTransferRules.looksLikeTextPayload("<html><body>login".toByteArray(), 17)).isTrue()
        assertThat(DownloadTransferRules.looksLikeTextPayload("{\"status\":\"failed\"}".toByteArray(), 20)).isTrue()
        assertThat(DownloadTransferRules.looksLikeTextPayload("[1,2,3]".toByteArray(), 7)).isTrue()
        assertThat(
            DownloadTransferRules.looksLikeTextPayload(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), ' '.code.toByte(), '<'.code.toByte()),
                5,
            ),
        ).isTrue()
        assertThat(DownloadTransferRules.looksLikeTextPayload("   \n\t".toByteArray(), 6)).isFalse()
        assertThat(DownloadTransferRules.looksLikeTextPayload("ID3\u0004".toByteArray(), 4)).isFalse()
        assertThat(DownloadTransferRules.looksLikeTextPayload("RIFF....WAVE".toByteArray(), 12)).isFalse()
        assertThat(DownloadTransferRules.looksLikeTextPayload(ByteArray(0), 0)).isFalse()
    }

    @Test
    fun `content range grammar accepts only complete well formed ranges`() {
        assertThat(DownloadTransferRules.parseContentRange("bytes 100-199/200"))
            .isEqualTo(ContentRange(100, 199, 200))
        assertThat(DownloadTransferRules.parseContentRange("Bytes 0-0/1"))
            .isEqualTo(ContentRange(0, 0, 1))
        assertThat(DownloadTransferRules.parseContentRange("bytes 10-19/*"))
            .isEqualTo(ContentRange(10, 19, null))
        assertThat(DownloadTransferRules.parseContentRange(null)).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bits 0-1/2")).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bytes 0-1")).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bytes -1-2/3")).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bytes 5-4/10")).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bytes 0-9/5")).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bytes x-9/10")).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bytes 0-9/ten")).isNull()
        assertThat(DownloadTransferRules.parseContentRange("bytes 0-9/")).isNull()
    }

    @Test
    fun `only strong etags validate a range`() {
        assertThat(DownloadTransferRules.strongEtag("\"abc\"")).isEqualTo("\"abc\"")
        assertThat(DownloadTransferRules.strongEtag("W/\"abc\"")).isNull()
        assertThat(DownloadTransferRules.strongEtag("\"has space\"")).isEqualTo("\"has space\"")
        assertThat(DownloadTransferRules.strongEtag("unquoted token")).isNull()
        assertThat(DownloadTransferRules.strongEtag("token")).isNull()
        assertThat(DownloadTransferRules.strongEtag("bad\nvalue")).isNull()
        assertThat(DownloadTransferRules.strongEtag("x".repeat(DownloadTransferRules.MAX_VALIDATOR_LENGTH + 1)))
            .isNull()
        assertThat(DownloadTransferRules.strongEtag(null)).isNull()
    }

    @Test
    fun `a part may only finalize when complete`() {
        assertThat(DownloadTransferRules.canFinalize(100, 100)).isTrue()
        assertThat(DownloadTransferRules.canFinalize(100, null)).isTrue()
        assertThat(DownloadTransferRules.canFinalize(99, 100)).isFalse()
        assertThat(DownloadTransferRules.canFinalize(0, 0)).isFalse()
        assertThat(DownloadTransferRules.canFinalize(0, null)).isFalse()
    }

    @Test
    fun `progress writes are throttled but forced at boundaries`() {
        val throttle = DownloadProgressThrottle(minIntervalMs = 250)

        assertThat(throttle.shouldEmit(0, 1_000)).isTrue()
        assertThat(throttle.shouldEmit(0, 2_000)).isFalse()
        assertThat(throttle.shouldEmit(1, 1_100)).isFalse()
        assertThat(throttle.shouldEmit(2, 1_300)).isTrue()
        assertThat(throttle.shouldEmit(3, 1_400)).isFalse()
        assertThat(throttle.shouldEmit(100, 1_450)).isTrue()
        assertThat(throttle.shouldEmit(100, 5_000)).isFalse()
    }

    @Test
    fun `indeterminate progress transition is written once`() {
        val throttle = DownloadProgressThrottle(minIntervalMs = 10_000)

        assertThat(throttle.shouldEmit(-1, 1_000)).isTrue()
        assertThat(throttle.shouldEmit(-1, 2_000)).isFalse()
        assertThat(throttle.shouldEmit(0, 2_001)).isTrue()
        assertThat(throttle.shouldEmit(1, 2_002)).isFalse()
        assertThat(throttle.shouldEmit(-1, 2_003)).isTrue()
    }

    @Test
    fun `failure and park text carry no connection detail`() {
        DownloadTransferFailure.entries.forEach { failure ->
            assertThat(failure.description()).doesNotContain("://")
            assertThat(failure.description()).doesNotContain("token")
            assertThat(failure.description()).doesNotContain("?")
        }
        DownloadTransferPark.entries.forEach { park ->
            assertThat(park.description()).doesNotContain("://")
            assertThat(park.description()).doesNotContain("token")
        }
    }
}
