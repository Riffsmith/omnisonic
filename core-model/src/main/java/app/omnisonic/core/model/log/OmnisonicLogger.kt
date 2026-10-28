package app.omnisonic.core.model.log

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
}

class ConsoleLogSink : LogSink {
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        println("${level.name.first()}/$tag: $message")
    }
}

class OmnisonicLogger(
    private val sink: LogSink,
    private val minLevel: LogLevel = LogLevel.DEBUG,
    private val redactor: CredentialRedactor = CredentialRedactor(),
) {
    fun debug(tag: String, message: String, error: Throwable? = null) {
        write(LogLevel.DEBUG, tag, message, error)
    }

    fun info(tag: String, message: String, error: Throwable? = null) {
        write(LogLevel.INFO, tag, message, error)
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        write(LogLevel.WARN, tag, message, error)
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        write(LogLevel.ERROR, tag, message, error)
    }

    private fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level < minLevel) return
        val scrubbed = redactor.scrub(message)
        val withCause = error?.let { "$scrubbed: ${redactor.scrub(it.toString())}" } ?: scrubbed
        sink.write(level, tag, withCause, error)
    }
}
