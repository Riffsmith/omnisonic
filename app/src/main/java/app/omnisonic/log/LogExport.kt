package app.omnisonic.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.omnisonic.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the user-facing export: every stream under `filesDir/logs` plus a
 * small metadata file, zipped into `cacheDir/log_exports/` and shared as one
 * `content://<applicationId>.fileprovider/...` URI. No permission is needed;
 * the read grant is attached to the `ACTION_SEND` intent and the chooser
 * wrapper carries it to the receiving app.
 */
object LogExport {

    const val LOGS_DIR = "logs"
    const val EXPORT_DIR = "log_exports"
    const val INFO_FILE = "export-info.txt"
    const val FORMAT_VERSION = 1
    const val AUTHORITY_SUFFIX = ".fileprovider"

    /** Returns the zip, or null when the export directory is not writable. */
    fun exportZip(context: Context, now: Long = System.currentTimeMillis()): File? {
        val logsDir = File(context.filesDir, LOGS_DIR)
        val exportDir = File(context.cacheDir, EXPORT_DIR)
        val zip = File(exportDir, "omnisonic-logs-$now.zip")
        return runCatching {
            exportDir.mkdirs()
            // Cache is disposable; stale exports would otherwise accumulate.
            exportDir.listFiles()?.forEach { it.delete() }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { output ->
                logsDir.listFiles()
                    .orEmpty()
                    .filter { it.isFile }
                    .sortedBy { it.name }
                    .forEach { file ->
                        output.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(output) }
                        output.closeEntry()
                    }
                output.putNextEntry(ZipEntry(INFO_FILE))
                output.write(infoText(now).toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
            zip
        }.getOrNull()
    }

    /** Chooser around the export; null when the zip could not be written. */
    fun shareIntent(context: Context, now: Long = System.currentTimeMillis()): Intent? {
        val zip = exportZip(context, now) ?: return null
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun infoText(now: Long): String = buildString {
        appendLine("Omnisonic log export")
        appendLine("format-version: $FORMAT_VERSION")
        appendLine("app-version: ${BuildConfig.VERSION_NAME}")
        appendLine("build-type: ${if (BuildConfig.DEBUG) "debug" else "release"}")
        appendLine("exported-at: ${Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).format(TIMESTAMP_FORMAT)}")
    }

    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
}
