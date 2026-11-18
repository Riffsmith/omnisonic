package app.omnisonic.log

import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CrashLogInstallerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun crashLog(): RotatingLogFile = RotatingLogFile(tempDir.toFile(), "crash")

    private fun crashInstaller(
        crash: RotatingLogFile,
        runtimeEntries: MutableList<String>,
    ): CrashLogInstaller = CrashLogInstaller(
        crashLogger = OmnisonicLogger(
            FileLogSink(crash, syncWrites = true, lockWaitMs = 2_000),
        ),
        runtimeLogger = OmnisonicLogger(
            LogSink { _, _, message, _ -> runtimeEntries += message },
        ),
    )

    @Test
    fun writesCrashLogMirrorsRuntimeAndDelegatesOnceThenRestores() {
        val crash = crashLog()
        val runtimeEntries = mutableListOf<String>()
        val installer = crashInstaller(crash, runtimeEntries)
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val delegated = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, error -> delegated += error }
        Thread.setDefaultUncaughtExceptionHandler(previous)

        try {
            installer.install()
            val installed = Thread.getDefaultUncaughtExceptionHandler()!!
            installed.uncaughtException(Thread.currentThread(), IllegalStateException("password=hunter2"))

            assertThat(delegated).hasSize(1)
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNotEqualTo(previous)
        } finally {
            installer.uninstall()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isEqualTo(original)
        val crashText = crash.currentFile.readText()
        assertThat(crashText).contains("FATAL exception on thread")
        assertThat(crashText).contains("password=[REDACTED]")
        assertThat(crashText).contains("CrashLogInstallerTest")
        assertThat(crashText).doesNotContain("hunter2")
        assertThat(runtimeEntries).hasSize(1)
        assertThat(runtimeEntries.single()).contains("FATAL exception")
    }

    @Test
    fun aFailingCrashWriterStillDelegates() {
        val runtimeEntries = mutableListOf<String>()
        val installer = CrashLogInstaller(
            crashLogger = OmnisonicLogger(LogSink { _, _, _, _ -> throw IllegalStateException("disk gone") }),
            runtimeLogger = OmnisonicLogger(LogSink { _, _, message, _ -> runtimeEntries += message }),
        )
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val delegated = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> delegated += error }

        try {
            installer.install()
            installer.handle(Thread.currentThread(), RuntimeException("boom"))
        } finally {
            installer.uninstall()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }

        assertThat(delegated).hasSize(1)
        assertThat(runtimeEntries).hasSize(1)
    }

    @Test
    fun uninstallRestoresThePreviousHandler() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val installer = crashInstaller(crashLog(), mutableListOf())

        try {
            installer.install()
            installer.install()
            assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNotEqualTo(previous)
        } finally {
            installer.uninstall()
            Thread.setDefaultUncaughtExceptionHandler(original)
        }

        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isEqualTo(original)
    }
}
