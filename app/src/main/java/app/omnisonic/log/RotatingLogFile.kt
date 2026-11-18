package app.omnisonic.log

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Size-capped append-only file with delete-oldest rotation:
 * `<name>.log`, `<name>.1` … `<name>.<maxFiles - 1>`. The newest line always
 * lands in `<name>.log`.
 *
 * Appends are serialised by one [ReentrantLock]: [append] blocks (the calling
 * logger is already on a request/transport path), while [appendWithin] gives
 * the crash path a bounded tryLock so a lock held by a dying thread can never
 * hang the fatal handler. No method throws; a failed write returns `false`.
 */
class RotatingLogFile(
    private val directory: File,
    private val name: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    private val lock = ReentrantLock()

    val currentFile: File
        get() = File(directory, "$name.log")

    /** Existing stream files, newest first (the base file first). */
    fun existingFiles(): List<File> = (0 until maxFiles.coerceAtLeast(1))
        .map(::fileAt)
        .filter { it.isFile }

    fun append(line: String, sync: Boolean = false): Boolean {
        lock.lock()
        return try {
            writeLocked(line, sync)
        } finally {
            lock.unlock()
        }
    }

    /** Bounded variant for the crash path: gives up rather than hang. */
    fun appendWithin(line: String, waitMs: Long, sync: Boolean = true): Boolean {
        val acquired = try {
            lock.tryLock(waitMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return false
        return try {
            writeLocked(line, sync)
        } finally {
            lock.unlock()
        }
    }

    private fun writeLocked(line: String, sync: Boolean): Boolean = try {
        val bytes = line.toByteArray(Charsets.UTF_8)
        directory.mkdirs()
        val current = currentFile
        if (current.isFile && current.length() + bytes.size > maxBytes) rotate()
        FileOutputStream(currentFile, true).use { output ->
            output.write(bytes)
            output.flush()
            if (sync) output.fd.sync()
        }
        true
    } catch (throwable: Throwable) {
        false
    }

    private fun rotate() {
        if (maxFiles <= 1) {
            currentFile.delete()
            return
        }
        val oldest = fileAt(maxFiles - 1)
        if (oldest != currentFile && oldest.isFile) oldest.delete()
        for (index in maxFiles - 2 downTo 1) {
            val source = fileAt(index)
            if (source.isFile) source.renameTo(fileAt(index + 1))
        }
        val current = currentFile
        if (current.isFile) current.renameTo(fileAt(1))
    }

    private fun fileAt(index: Int): File =
        if (index == 0) currentFile else File(directory, "$name.$index")

    companion object {
        const val DEFAULT_MAX_BYTES = 1L * 1024 * 1024
        const val DEFAULT_MAX_FILES = 3
    }
}
