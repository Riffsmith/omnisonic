package app.omnisonic.log

import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [LogSink] that appends one formatted record per event to a [RotatingLogFile].
 * The [OmnisonicLogger] has already redacted the message and throwable, so this
 * sink only formats, writes and swallows I/O failures.
 *
 * [syncWrites] and [lockWaitMs] exist for the crash stream: it fsyncs and may
 * wait briefly for the file lock without ever hanging the fatal handler.
 */
class FileLogSink(
    private val file: RotatingLogFile,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val syncWrites: Boolean = false,
    private val lockWaitMs: Long = -1L,
) : LogSink {

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val line = format(level, tag, message, error)
        if (lockWaitMs >= 0) {
            file.appendWithin(line, lockWaitMs, sync = syncWrites)
        } else {
            file.append(line, sync = syncWrites)
        }
    }

    private fun format(level: LogLevel, tag: String, message: String, error: Throwable?): String =
        buildString {
            append(clock().atZone(zone).format(TIMESTAMP_FORMAT))
            append(' ').append(level.name.padEnd(5)).append(' ')
            append('[').append(Thread.currentThread().name).append("] ")
            append(tag).append(": ").append(message).append('\n')
            if (error != null) {
                val stack = StringWriter()
                PrintWriter(stack).use { error.printStackTrace(it) }
                append(stack.toString())
            }
        }

    private companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
