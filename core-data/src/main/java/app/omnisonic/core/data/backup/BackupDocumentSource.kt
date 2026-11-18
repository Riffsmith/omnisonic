package app.omnisonic.core.data.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream

fun interface BackupDocumentSource {
    fun open(): InputStream
}

/** Reads a SAF document previously picked with `ActivityResultContracts.OpenDocument`. */
class SafBackupDocumentSource(
    private val contentResolver: ContentResolver,
    private val documentUri: Uri,
) : BackupDocumentSource {

    override fun open(): InputStream =
        contentResolver.openInputStream(documentUri)
            ?: throw IOException("SAF document is not readable: $documentUri")
}
