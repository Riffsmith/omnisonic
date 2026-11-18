package app.omnisonic.core.model.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TeeLogSinkTest {

    private class RecordingSink(private val name: String, private val log: MutableList<String>) : LogSink {
        override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
            log += "$name:$level:$tag:$message:${error?.message}"
        }
    }

    @Test
    fun writesToEverySink() {
        val log = mutableListOf<String>()
        val tee = TeeLogSink(RecordingSink("first", log), RecordingSink("second", log))

        tee.write(LogLevel.INFO, "Tag", "hello", null)

        assertThat(log).containsExactly("first:INFO:Tag:hello:null", "second:INFO:Tag:hello:null")
    }

    @Test
    fun keepsSinkOrder() {
        val log = mutableListOf<String>()
        val tee = TeeLogSink(
            RecordingSink("a", log),
            RecordingSink("b", log),
            RecordingSink("c", log),
        )

        tee.write(LogLevel.DEBUG, "Tag", "hello", null)

        assertThat(log.map { it.substringBefore(':') }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun aFailingSinkDoesNotStopTheOthers() {
        val log = mutableListOf<String>()
        val tee = TeeLogSink(
            LogSink { _, _, _, _ -> throw IllegalStateException("disk full") },
            RecordingSink("survivor", log),
        )

        tee.write(LogLevel.ERROR, "Tag", "still written", null)

        assertThat(log).containsExactly("survivor:ERROR:Tag:still written:null")
    }
}
