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
        // Sinks (Logcat in particular) print the throwable itself, so the raw
        // error must never reach them: hand over a scrubbed wrapper that keeps
        // the stack trace and cause chain.
        sink.write(level, tag, scrubbed, error?.scrubbed())
    }

    private fun Throwable.scrubbed(depth: Int = 0): Throwable {
        val copy = ScrubbedThrowable(redactor.scrub(message.orEmpty()), this)
        if (depth < MAX_CAUSE_SCRUB_DEPTH) {
            cause?.let { copy.initCause(it.scrubbed(depth + 1)) }
        }
        return copy
    }

    /** Same class shape and stack trace as the original, redacted message. */
    private class ScrubbedThrowable(message: String, original: Throwable) : Throwable(message) {
        init {
            stackTrace = original.stackTrace
        }
    }

    private companion object {
        private const val MAX_CAUSE_SCRUB_DEPTH = 8
    }
}
