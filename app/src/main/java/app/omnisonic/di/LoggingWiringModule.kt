package app.omnisonic.di

import android.content.Context
import app.omnisonic.BuildConfig
import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.log.TeeLogSink
import app.omnisonic.core.network.NetworkLogGate
import app.omnisonic.log.AndroidLogSink
import app.omnisonic.log.CrashLogInstaller
import app.omnisonic.log.FileLogSink
import app.omnisonic.log.RotatingLogFile
import app.omnisonic.log.SettingsNetworkLogGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RuntimeLogFile

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NetworkLogFile

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrashLogFile

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NetworkLogger

/**
 * One process-wide logger for production wiring. The sink is a tee of logcat
 * (unchanged behaviour) and the rotating `runtime.log` file, so diagnostics
 * survive both release builds and a lost adb session. Every file line has
 * already passed through [OmnisonicLogger] and its credential redactor.
 *
 * File logging is on in both build types; release raises the level to INFO.
 * Network HTTP logging has its own stream and its own logger, and is opt-in
 * through [SettingsNetworkLogGate]. Each of the three streams rotates at
 * 3 x 1 MB independently.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingWiringModule {

    private const val LOG_DIR = "logs"
    private const val CRASH_LOCK_WAIT_MS = 2_000L

    @Provides
    @Singleton
    @RuntimeLogFile
    fun runtimeLogFile(@ApplicationContext context: Context): RotatingLogFile =
        RotatingLogFile(logDirectory(context), "runtime")

    @Provides
    @Singleton
    @NetworkLogFile
    fun networkLogFile(@ApplicationContext context: Context): RotatingLogFile =
        RotatingLogFile(logDirectory(context), "network")

    @Provides
    @Singleton
    @CrashLogFile
    fun crashLogFile(@ApplicationContext context: Context): RotatingLogFile =
        RotatingLogFile(logDirectory(context), "crash")

    @Provides
    @Singleton
    fun logSink(@RuntimeLogFile runtimeLog: RotatingLogFile): LogSink =
        TeeLogSink(AndroidLogSink(), FileLogSink(runtimeLog))

    @Provides
    @Singleton
    fun omnisonicLogger(sink: LogSink): OmnisonicLogger =
        OmnisonicLogger(sink, minLevel = fileMinLevel())

    @Provides
    @Singleton
    @NetworkLogger
    fun networkLogger(@NetworkLogFile networkLog: RotatingLogFile): OmnisonicLogger =
        OmnisonicLogger(FileLogSink(networkLog), minLevel = fileMinLevel())

    @Provides
    @Singleton
    fun crashLogInstaller(
        @CrashLogFile crashLog: RotatingLogFile,
        logger: OmnisonicLogger,
    ): CrashLogInstaller = CrashLogInstaller(
        crashLogger = OmnisonicLogger(
            FileLogSink(crashLog, syncWrites = true, lockWaitMs = CRASH_LOCK_WAIT_MS),
            // Same level policy as the other two streams; a fatal trace is
            // ERROR, so it passes in both build types.
            minLevel = fileMinLevel(),
        ),
        runtimeLogger = logger,
    )

    @Provides
    @Singleton
    fun networkLogGate(gate: SettingsNetworkLogGate): NetworkLogGate = gate

    private fun fileMinLevel(): LogLevel =
        if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO

    private fun logDirectory(context: Context): File = File(context.filesDir, LOG_DIR)
}
