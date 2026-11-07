package app.omnisonic.core.data

import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger

/**
 * Silent logger for core-data wiring tests. Production wiring always injects
 * the shared Android-backed logger; the registry no longer falls back to a
 * console sink on its own.
 */
internal val silentTestLogger = OmnisonicLogger(LogSink { _, _, _, _ -> })
