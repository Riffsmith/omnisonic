package app.omnisonic.log

import app.omnisonic.core.model.log.LogLevel
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileLogSinkTest {

    @TempDir
    lateinit var tempDir: Path

    private val fixedClock = { Instant.parse("2026-11-18T10:15:30.123Z") }

    private fun sink(log: RotatingLogFile): FileLogSink =
        FileLogSink(log, clock = fixedClock, zone = ZoneOffset.UTC)

    @Test
    fun appendsFormattedLineWithLevelTagAndMessage() {
        val log = RotatingLogFile(tempDir.toFile(), "runtime")

        sink(log).write(LogLevel.WARN, "Sync", "fallback provider", null)

        val line = log.currentFile.readText()
        assertThat(line).startsWith("2026-11-18T10:15:30.123 WARN ")
        assertThat(line).contains("[${Thread.currentThread().name}] Sync: fallback provider")
    }

    @Test
    fun passesLevelsThroughAndAppendsAcrossSinkInstances() {
        val log = RotatingLogFile(tempDir.toFile(), "runtime")
        sink(log).write(LogLevel.DEBUG, "Tag", "first", null)

        sink(RotatingLogFile(tempDir.toFile(), "runtime")).write(LogLevel.ERROR, "Tag", "second", null)

        val text = log.currentFile.readText()
        assertThat(text).contains("DEBUG")
        assertThat(text).contains("first")
        assertThat(text).contains("ERROR")
        assertThat(text).contains("second")
    }

    @Test
    fun appendsTheThrowableStackTrace() {
        val log = RotatingLogFile(tempDir.toFile(), "runtime")

        sink(log).write(
            LogLevel.ERROR,
            "Playback",
            "engine failed",
            IllegalStateException("decoder gave up"),
        )

        val text = log.currentFile.readText()
        assertThat(text).contains("engine failed")
        assertThat(text).contains("java.lang.IllegalStateException: decoder gave up")
        assertThat(text).contains("FileLogSinkTest")
    }

    @Test
    fun swallowsIoFailures() {
        val occupied = File(tempDir.toFile(), "occupied")
        occupied.writeText("not a directory")
        val log = RotatingLogFile(occupied, "runtime")

        sink(log).write(LogLevel.INFO, "Tag", "lost but harmless", null)

        assertThat(occupied.readText()).isEqualTo("not a directory")
    }
}
