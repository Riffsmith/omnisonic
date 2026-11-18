package app.omnisonic.core.model.log

/**
 * Fans one event out to every sink in order. A sink that throws (full disk,
 * closed stream, platform failure) is skipped so it cannot silence the others
 * — logcat must keep working even when the file writer does not.
 */
class TeeLogSink(private val sinks: List<LogSink>) : LogSink {

    constructor(vararg sinks: LogSink) : this(sinks.toList())

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        sinks.forEach { sink -> runCatching { sink.write(level, tag, message, error) } }
    }
}
