package app.omnisonic.core.data.downloads

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import app.omnisonic.core.model.log.OmnisonicLogger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Public Downloads export over `MediaStore.Downloads` (Phase 7 task 6a). Every
 * document is inserted pending in `Download/Omnisonic/`, written with truncation
 * (never an append), read back for a checksum match, then cleared before the
 * queue row settles. `is_music = 0` keeps the artifact out of the audio
 * collection so a later local-library index cannot treat it as music.
 *
 * minSdk 29 means the scoped-storage job for an app-owned document needs no
 * storage permission at all; there is deliberately no legacy pre-29 branch and
 * no broad-permission request anywhere in the export path.
 */
class MediaStoreExportDocumentStore(
    private val resolver: ContentResolver,
    private val logger: OmnisonicLogger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExportDocumentStore {

    override suspend fun insertPending(request: ExportDocumentRequest): ExportDocumentRef? =
        withContext(ioDispatcher) {
            try {
                val uri = resolver.insert(downloadsCollection(), pendingValues(request))
                uri?.toString()?.takeIf(::isOwnedExportDocumentUri)?.let(::ExportDocumentRef)
            } catch (_: IllegalArgumentException) {
                logUnavailable("insert")
                null
            } catch (_: SecurityException) {
                logUnavailable("insert")
                null
            } catch (_: IOException) {
                logUnavailable("insert")
                null
            }
        }

    override suspend fun writeTruncating(
        ref: ExportDocumentRef,
        source: File,
    ): ExportCopyOutcome = withContext(ioDispatcher) {
        val uri = ownedUri(ref) ?: return@withContext ExportCopyOutcome.WriteFailed
        val digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (_: Exception) {
            return@withContext ExportCopyOutcome.WriteFailed
        }
        var copied = 0L
        val written = try {
            resolver.openOutputStream(uri, TRUNCATING_WRITE).use { output ->
                if (output == null) return@withContext ExportCopyOutcome.WriteFailed
                source.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read
                    }
                }
                output.flush()
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!written) return@withContext ExportCopyOutcome.WriteFailed
        if (copied != source.length()) return@withContext ExportCopyOutcome.SizeMismatch
        val readBack = readBackDigest(uri) ?: return@withContext ExportCopyOutcome.WriteFailed
        if (!MessageDigest.isEqual(digest.digest(), readBack)) {
            return@withContext ExportCopyOutcome.DigestMismatch
        }
        ExportCopyOutcome.Copied(copied)
    }

    override suspend fun clearPending(ref: ExportDocumentRef): Boolean = withContext(ioDispatcher) {
        val uri = ownedUri(ref) ?: return@withContext false
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        try {
            resolver.update(uri, values, null, null) == 1
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun delete(ref: ExportDocumentRef): Boolean = withContext(ioDispatcher) {
        val uri = ownedUri(ref) ?: return@withContext false
        try {
            resolver.delete(uri, null, null) >= 1
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun presence(ref: ExportDocumentRef): ExportDocumentPresence = withContext(ioDispatcher) {
        val uri = ownedUri(ref) ?: return@withContext ExportDocumentPresence.UNKNOWN
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext ExportDocumentPresence.MISSING
                val pending = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                if (pending < 0) return@withContext ExportDocumentPresence.UNKNOWN
                return@withContext if (cursor.getInt(pending) == 1) {
                    ExportDocumentPresence.PENDING
                } else {
                    ExportDocumentPresence.VISIBLE
                }
            } ?: ExportDocumentPresence.UNKNOWN
        } catch (_: IOException) {
            ExportDocumentPresence.UNKNOWN
        } catch (_: SecurityException) {
            ExportDocumentPresence.UNKNOWN
        } catch (_: IllegalArgumentException) {
            ExportDocumentPresence.UNKNOWN
        }
    }

    override suspend fun pendingMatches(mediaKey: String, title: String?): List<PendingExportDocument>? =
        withContext(ioDispatcher) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            try {
                resolver.query(
                    downloadsCollection(),
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1",
                    arrayOf(ExportNaming.RELATIVE_PATH),
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) return@withContext null
                    val matches = mutableListOf<PendingExportDocument>()
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: continue
                        if (!ExportNaming.matchesDocumentName(name, mediaKey, title)) continue
                        val uri = ContentUris.withAppendedId(downloadsCollection(), cursor.getLong(idColumn)).toString()
                        if (!isOwnedExportDocumentUri(uri)) return@withContext null
                        matches += PendingExportDocument(
                            ExportDocumentRef(uri),
                            name,
                            cursor.getString(mimeColumn) ?: ExportNaming.MIME_FALLBACK,
                        )
                    }
                    matches
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    // This module deliberately has no core-ktx dependency, so the KTX `toUri` suggestion does not apply.
    @SuppressLint("UseKtx")
    private fun ownedUri(ref: ExportDocumentRef): Uri? =
        if (isOwnedExportDocumentUri(ref.uri)) Uri.parse(ref.uri) else null

    private fun readBackDigest(uri: Uri): ByteArray? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri).use { input ->
            if (input == null) return null
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun logUnavailable(action: String) {
        logger.warn(TAG, "public export $action unavailable")
    }

    companion object {
        const val TRUNCATING_WRITE = "wt"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val TAG = "ExportDocument"

        /** Pending insert values; `is_music = 0` keeps the artifact out of the local audio index. */
        fun pendingValues(request: ExportDocumentRequest): ContentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, request.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, request.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
        }

        fun downloadsCollection(): Uri =
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
