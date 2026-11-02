package app.omnisonic.log

import android.util.Log
import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink

/**
 * Logcat sink so release builds do not lose diagnostics to println. The
 * OmnisonicLogger hands over already-redacted messages and throwables; this
 * sink only maps levels onto Log priorities.
 */
class AndroidLogSink : LogSink {

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, error)
            LogLevel.INFO -> Log.i(tag, message, error)
            LogLevel.WARN -> Log.w(tag, message, error)
            LogLevel.ERROR -> Log.e(tag, message, error)
        }
    }
}
