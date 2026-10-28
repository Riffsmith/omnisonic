package app.omnisonic.core.model.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OmnisonicLoggerTest {

    private class RecordingSink : LogSink {
        val entries = mutableListOf<Triple<LogLevel, String, String>>()

        override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
            entries += Triple(level, tag, message)
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

        assertThat(sink.entries.single().third)
            .isEqualTo("auth failed: java.lang.IllegalStateException: password=[REDACTED]")
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
