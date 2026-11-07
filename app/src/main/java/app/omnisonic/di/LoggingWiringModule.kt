package app.omnisonic.di

import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.log.AndroidLogSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * One process-wide logger for production wiring. The sink writes to logcat
 * (already redacted by [OmnisonicLogger]), so diagnostics survive release
 * builds instead of being lost to stdout. Library modules keep their own
 * defaults for tests; production always injects this instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingWiringModule {

    @Provides
    @Singleton
    fun logSink(): LogSink = AndroidLogSink()

    @Provides
    @Singleton
    fun omnisonicLogger(sink: LogSink): OmnisonicLogger = OmnisonicLogger(sink)
}
