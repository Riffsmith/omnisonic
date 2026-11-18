package app.omnisonic.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogExportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, LogExport.LOGS_DIR).mkdirs()
        File(context.cacheDir, LogExport.EXPORT_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, LogExport.LOGS_DIR).deleteRecursively()
        File(context.cacheDir, LogExport.EXPORT_DIR).deleteRecursively()
    }

    private fun writeLog(name: String, content: String) {
        File(File(context.filesDir, LogExport.LOGS_DIR), name).writeText(content)
    }

    private fun entries(zip: File): Map<String, String> = buildMap {
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                put(entry.name, input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun zipContainsEveryLogStreamAndExportInfo() {
        writeLog("runtime.log", "runtime line\n")
        writeLog("network.log", "network line\n")
        writeLog("crash.log", "crash line\n")

        val zip = LogExport.exportZip(context, now = 1_234L)

        assertThat(zip).isNotNull()
        val entries = entries(zip!!)
        assertThat(entries.keys).containsAtLeast("runtime.log", "network.log", "crash.log", LogExport.INFO_FILE)
        assertThat(entries.getValue("runtime.log")).isEqualTo("runtime line\n")
        assertThat(entries.getValue(LogExport.INFO_FILE)).contains("format-version: 1")
        assertThat(entries.getValue(LogExport.INFO_FILE)).contains("app-version: ")
        assertThat(entries.getValue(LogExport.INFO_FILE)).contains("build-type: ")
        assertThat(zip.name).isEqualTo("omnisonic-logs-1234.zip")
    }

    @Test
    fun exportReplacesStaleZipsInTheCacheDirectory() {
        writeLog("runtime.log", "line\n")
        val stale = File(File(context.cacheDir, LogExport.EXPORT_DIR), "omnisonic-logs-1.zip")
        stale.parentFile?.mkdirs()
        stale.writeText("stale")

        LogExport.exportZip(context, now = 2_000L)

        val remaining = File(context.cacheDir, LogExport.EXPORT_DIR).listFiles().orEmpty().map { it.name }
        assertThat(remaining).containsExactly("omnisonic-logs-2000.zip")
    }

    @Test
    fun shareIntentIsAChooserWithAZipReadGrant() {
        writeLog("runtime.log", "line\n")

        val chooser = LogExport.shareIntent(context, now = 77L)

        assertThat(chooser).isNotNull()
        assertThat(chooser!!.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT) as Intent?
        assertThat(send).isNotNull()
        assertThat(send!!.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("application/zip")
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM) as Uri?
        assertThat(uri).isNotNull()
        assertThat(uri!!.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(context.packageName + LogExport.AUTHORITY_SUFFIX)
    }
}
