package app.omnisonic.core.model.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OmnisonicLoggerTest {

    private class RecordingSink : LogSink {
        val entries = mutableListOf<Triple<LogLevel, String, String>>()
        val errors = mutableListOf<Throwable?>()

        override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
            entries += Triple(level, tag, message)
            errors += error
        }
    }

    @Test
    fun scrubsCredentialsInMessage() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink)

        logger.info("HttpClient", "GET https://host/ping?u=admin&p=hunter2")

        assertThat(sink.entries.single().third)
            .isEqualTo("GET https://host/ping?u=[REDACTED]&p=[REDACTED]")
    }

    @Test
    fun scrubsExceptionText() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink)

        logger.warn("Sync", "auth failed", IllegalStateException("password=hunter2"))

        assertThat(sink.entries.single().third).isEqualTo("auth failed")
        val reported = sink.errors.single()!!
        assertThat(reported.message).isEqualTo("password=[REDACTED]")
    }

    @Test
    fun sinksReceiveAScrubbedThrowableNotTheRawError() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink)
        val original = IllegalStateException("password=hunter2 and apiKey=abcd1234")

        logger.error("Sync", "sync failed", original)

        val reported = sink.errors.single()
        assertThat(reported).isNotNull()
        assertThat(reported!!.message).contains("[REDACTED]")
        assertThat(reported.message).doesNotContain("hunter2")
        assertThat(reported.message).doesNotContain("abcd1234")
    }

    @Test
    fun scrubbedThrowableKeepsStackTraceAndCauseChain() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink)
        val cause = IllegalStateException("token=supersecrettoken")
        val original = RuntimeException("password=hunter2", cause)

        logger.error("Sync", "failed", original)

        val reported = sink.errors.single()!!
        assertThat(reported.stackTrace).isEqualTo(original.stackTrace)
        assertThat(reported.cause).isNotNull()
        assertThat(reported.cause!!.message).contains("[REDACTED]")
        assertThat(reported.cause!!.message).doesNotContain("supersecrettoken")
    }

    @Test
    fun scrubbedThrowableRedactsNestedCauseMessages() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink)
        val root = RuntimeException("secret=abc123")
        val middle = RuntimeException("password=letmein", root)

        logger.error("Sync", "failed", middle)

        val reported = sink.errors.single()!!
        assertThat(reported.message).doesNotContain("letmein")
        assertThat(reported.cause!!.message).doesNotContain("abc123")
    }

    @Test
    fun passesNullErrorThroughUnchanged() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink)

        logger.info("Sync", "ok")

        assertThat(sink.errors.single()).isNull()
    }

    @Test
    fun suppressesBelowMinLevel() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink, minLevel = LogLevel.WARN)

        logger.debug("a", "one")
        logger.info("a", "two")
        logger.warn("a", "three")

        assertThat(sink.entries.map { it.first }).containsExactly(LogLevel.WARN).inOrder()
    }

    @Test
    fun writesAllAtOrAboveMinLevel() {
        val sink = RecordingSink()
        val logger = OmnisonicLogger(sink)

        logger.debug("t", "d")
        logger.info("t", "i")
        logger.error("t", "e")

        assertThat(sink.entries.map { it.first })
            .containsExactly(LogLevel.DEBUG, LogLevel.INFO, LogLevel.ERROR)
            .inOrder()
    }
}
