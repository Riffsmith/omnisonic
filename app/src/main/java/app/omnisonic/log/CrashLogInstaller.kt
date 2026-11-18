package app.omnisonic.log

import app.omnisonic.core.model.log.OmnisonicLogger

/**
 * Synchronous uncaught-exception handler. It writes the fatal trace through
 * the already-redacting [OmnisonicLogger] into its own `crash.log` stream
 * (fsynced, bounded lock wait), mirrors the same entry into the runtime stream,
 * and then always delegates to the handler that was installed before this one.
 *
 * The write happens on the crashing thread on purpose: the process is about to
 * die, so a coroutine or executor hop could lose the trace. Native crashes
 * (SIGSEGV) and OOM kills never reach this handler.
 */
class CrashLogInstaller(
    private val crashLogger: OmnisonicLogger,
    private val runtimeLogger: OmnisonicLogger? = null,
) {
    private val installLock = Any()

    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var installed = false

    fun install() {
        synchronized(installLock) {
            if (installed) return
            previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error -> handle(thread, error) }
            installed = true
        }
    }

    fun uninstall() {
        synchronized(installLock) {
            if (!installed) return
            Thread.setDefaultUncaughtExceptionHandler(previous)
            previous = null
            installed = false
        }
    }

    fun handle(thread: Thread, error: Throwable) {
        val message = "FATAL exception on thread ${thread.name}"
        try {
            runCatching { crashLogger.error(TAG, message, error) }
            runCatching { runtimeLogger?.error(TAG, message, error) }
        } finally {
            // Exactly once, and never swallowed even when there is no previous
            // handler (a test process or an embedded runtime may install none).
            previous?.uncaughtException(thread, error) ?: run { error.printStackTrace() }
        }
    }

    private companion object {
        const val TAG = "Crash"
    }
}
