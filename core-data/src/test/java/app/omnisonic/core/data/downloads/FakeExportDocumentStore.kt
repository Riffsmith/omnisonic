package app.omnisonic.core.data.downloads

import java.io.File

/**
 * In-memory public-document store for the export policy tests. It records every
 * call so a test can prove which documents were inserted, rewritten, cleared or
 * removed; behaviour can be made to fail at any single step.
 */
class FakeExportDocumentStore : ExportDocumentStore {

    class Document(
        val request: ExportDocumentRequest,
        var bytes: ByteArray,
        var pending: Boolean,
    ) {
        var deleted: Boolean = false
    }

    val documents = LinkedHashMap<String, Document>()
    var inserts: Int = 0
    var writes: Int = 0
    var clears: Int = 0
    var deletes: Int = 0
    var available: Boolean = true
    var failWrites: Boolean = false
    var failClears: Boolean = false
    var failDeletes: Boolean = false
    var unknownPresence: Boolean = false
    var unknownPendingMatches: Boolean = false
    var shortWrite: Boolean = false
    var corruptWrite: Boolean = false
    var beforeWrite: (suspend () -> Unit)? = null
    var afterClear: (suspend () -> Unit)? = null

    private var nextId = 1L

    fun visibleDocuments(): List<Document> = documents.values.filter { !it.pending }

    override suspend fun insertPending(request: ExportDocumentRequest): ExportDocumentRef? {
        if (!available) return null
        val uri = "content://media/external/downloads/${nextId++}"
        documents[uri] = Document(request, ByteArray(0), pending = true)
        inserts++
        return ExportDocumentRef(uri)
    }

    override suspend fun writeTruncating(ref: ExportDocumentRef, source: File): ExportCopyOutcome {
        beforeWrite?.invoke()
        writes++
        val document = documents[ref.uri] ?: return ExportCopyOutcome.WriteFailed
        if (failWrites) return ExportCopyOutcome.WriteFailed
        val bytes = source.readBytes()
        document.bytes = bytes
        if (shortWrite && bytes.isNotEmpty()) {
            document.bytes = bytes.copyOf(bytes.size - 1)
            return ExportCopyOutcome.SizeMismatch
        }
        if (corruptWrite) {
            document.bytes = bytes.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }
            return ExportCopyOutcome.DigestMismatch
        }
        return ExportCopyOutcome.Copied(bytes.size.toLong())
    }

    override suspend fun clearPending(ref: ExportDocumentRef): Boolean {
        clears++
        if (failClears) return false
        val document = documents[ref.uri] ?: return false
        document.pending = false
        afterClear?.invoke()
        return true
    }

    override suspend fun delete(ref: ExportDocumentRef): Boolean {
        deletes++
        if (failDeletes) return false
        val document = documents.remove(ref.uri) ?: return false
        document.deleted = true
        return true
    }

    override suspend fun presence(ref: ExportDocumentRef): ExportDocumentPresence {
        if (unknownPresence) return ExportDocumentPresence.UNKNOWN
        val document = documents[ref.uri] ?: return ExportDocumentPresence.MISSING
        return if (document.pending) ExportDocumentPresence.PENDING else ExportDocumentPresence.VISIBLE
    }

    override suspend fun pendingMatches(mediaKey: String, title: String?): List<PendingExportDocument>? {
        if (unknownPendingMatches) return null
        return documents.mapNotNull { (uri, document) ->
            if (!document.pending || !ExportNaming.matchesDocumentName(document.request.displayName, mediaKey, title)) {
                return@mapNotNull null
            }
            PendingExportDocument(ExportDocumentRef(uri), document.request.displayName, document.request.mimeType)
        }
    }
}
