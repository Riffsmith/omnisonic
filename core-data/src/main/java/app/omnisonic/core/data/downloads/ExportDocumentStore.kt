package app.omnisonic.core.data.downloads

import java.io.File

/**
 * Opaque handle to a document this app created. Recovery only ever resolves a
 * URI that passed [isOwnedExportDocumentUri]; another app's or user's document
 * is never adopted because its id was guessed.
 */
data class ExportDocumentRef(val uri: String)

enum class ExportDocumentPresence { PENDING, VISIBLE, MISSING, UNKNOWN }

data class PendingExportDocument(
    val ref: ExportDocumentRef,
    val displayName: String,
    val mimeType: String,
)

/** How one truncating copy into a public document ended. */
sealed interface ExportCopyOutcome {

    /** The document now holds every staged byte and the read-back checksum matches. */
    data class Copied(val bytes: Long) : ExportCopyOutcome

    /** The resolver refused the write, the staged file was unreadable, or the read-back failed. */
    data object WriteFailed : ExportCopyOutcome

    /** The document holds a different byte count than the staged file. */
    data object SizeMismatch : ExportCopyOutcome

    /** The document's read-back content does not match the staged checksum. */
    data object DigestMismatch : ExportCopyOutcome
}

/**
 * Seam over the public-document API (Phase 7 task 6a). Production is
 * [MediaStoreExportDocumentStore] over `MediaStore.Downloads`; tests supply an
 * in-memory store so the publication/copy/recovery policy is verifiable without
 * a device. Implementations must create every document pending, write with
 * truncation (never append), verify by read-back, and fail closed rather than
 * returning a partial success.
 */
interface ExportDocumentStore {

    /** Creates a pending document, or null when the resolver is unavailable. */
    suspend fun insertPending(request: ExportDocumentRequest): ExportDocumentRef?

    suspend fun writeTruncating(ref: ExportDocumentRef, source: File): ExportCopyOutcome

    /** Clears `IS_PENDING`; false means the document stays invisible. */
    suspend fun clearPending(ref: ExportDocumentRef): Boolean

    /** Deletes our own pending or completed document; false when it could not be removed. */
    suspend fun delete(ref: ExportDocumentRef): Boolean

    suspend fun presence(ref: ExportDocumentRef): ExportDocumentPresence

    /** Exact pending matches for an insert whose returned URI could not be journaled; null means unknown. */
    suspend fun pendingMatches(mediaKey: String, title: String?): List<PendingExportDocument>?
}

/**
 * A document URI is owned only when it points into the public Downloads
 * collection (`content://media/.../downloads/<id>`). The path is checked
 * segment-wise and the URI must carry no whitespace or control characters, so a
 * corrupt or tampered session file can never direct a delete/update at an
 * unrelated document.
 */
fun isOwnedExportDocumentUri(uri: String?): Boolean {
    val value = uri?.trim() ?: return false
    if (!value.startsWith("content://")) return false
    if (value.any { it.code <= 0x20 || it.code == 0x7F }) return false
    val withoutQuery = value.substringBefore('?').substringBefore('#')
    if (withoutQuery != value) return false
    val segments = withoutQuery.removePrefix("content://").split('/')
    if (segments.size < 3) return false
    if (segments[0] != "media") return false
    if (!segments.drop(1).any { it.equals("downloads", ignoreCase = true) }) return false
    val documentId = segments.last().toLongOrNull() ?: return false
    return documentId > 0L
}
