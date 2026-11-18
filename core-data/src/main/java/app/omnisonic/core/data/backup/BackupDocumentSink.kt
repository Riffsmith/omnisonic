package app.omnisonic.core.data.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.OutputStream

fun interface BackupDocumentSink {
    fun open(): OutputStream
}

/** Writes through a SAF document previously created by `ActivityResultContracts.CreateDocument`. */
class SafBackupDocumentSink(
    private val contentResolver: ContentResolver,
    private val documentUri: Uri,
) : BackupDocumentSink {

    override fun open(): OutputStream =
        contentResolver.openOutputStream(documentUri, "w")
            ?: throw IOException("SAF document is not writable: $documentUri")
}
