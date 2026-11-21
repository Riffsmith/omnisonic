package app.omnisonic.core.data.downloads

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import app.omnisonic.core.data.silentTestLogger
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreExportDocumentStoreTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val uri = Uri.parse("content://media/external_primary/downloads/42")
    private val ref = ExportDocumentRef(uri.toString())
    private val request = ExportNaming.request("stream:1:song-1:auto:auto", "Track", "flac")

    @Test
    fun `the real adapter truncates writes and verifies the read-back digest`() = runTest {
        val source = source(ByteArray(512) { (it % 251).toByte() })
        val output = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver> {
            every { openOutputStream(uri, "wt") } returns output
            every { openInputStream(uri) } answers { ByteArrayInputStream(output.toByteArray()) }
        }

        val outcome = store(resolver).writeTruncating(ref, source)

        assertThat(outcome).isEqualTo(ExportCopyOutcome.Copied(source.length()))
        assertThat(output.toByteArray()).isEqualTo(source.readBytes())
        verify(exactly = 1) { resolver.openOutputStream(uri, "wt") }
        verify(exactly = 1) { resolver.openInputStream(uri) }
    }

    @Test
    fun `the real adapter rejects corrupt read-back and write failures`() = runTest {
        val source = source(ByteArray(128) { it.toByte() })
        val corruptResolver = mockk<ContentResolver> {
            every { openOutputStream(uri, "wt") } returns ByteArrayOutputStream()
            every { openInputStream(uri) } returns ByteArrayInputStream(ByteArray(128) { 99 })
        }
        val failingResolver = mockk<ContentResolver> {
            every { openOutputStream(uri, "wt") } throws IOException("write failed")
        }

        assertThat(store(corruptResolver).writeTruncating(ref, source))
            .isEqualTo(ExportCopyOutcome.DigestMismatch)
        assertThat(store(failingResolver).writeTruncating(ref, source))
            .isEqualTo(ExportCopyOutcome.WriteFailed)
    }

    @Test
    fun `pending update and delete require an applied resolver write`() = runTest {
        val resolver = mockk<ContentResolver> {
            every { update(uri, any(), null, null) } returns 1 andThen 0
            every { delete(uri, null, null) } returns 1 andThen 0
        }
        val store = store(resolver)

        assertThat(store.clearPending(ref)).isTrue()
        assertThat(store.clearPending(ref)).isFalse()
        assertThat(store.delete(ref)).isTrue()
        assertThat(store.delete(ref)).isFalse()
        verify(exactly = 2) { resolver.update(uri, any(), null, null) }
        verify(exactly = 2) { resolver.delete(uri, null, null) }
    }

    @Test
    fun `presence distinguishes pending visible missing and unknown`() = runTest {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(uri, any(), null, null, null) } returnsMany listOf(
            cursor(arrayOf(MediaStore.MediaColumns.IS_PENDING), arrayOf(1)),
            cursor(arrayOf(MediaStore.MediaColumns.IS_PENDING), arrayOf(0)),
            cursor(arrayOf(MediaStore.MediaColumns.IS_PENDING)),
        )
        val store = store(resolver)

        assertThat(store.presence(ref)).isEqualTo(ExportDocumentPresence.PENDING)
        assertThat(store.presence(ref)).isEqualTo(ExportDocumentPresence.VISIBLE)
        assertThat(store.presence(ref)).isEqualTo(ExportDocumentPresence.MISSING)
        every { resolver.query(uri, any(), null, null, null) } throws SecurityException("unavailable")
        assertThat(store.presence(ref)).isEqualTo(ExportDocumentPresence.UNKNOWN)
    }

    @Test
    fun `pending recovery returns only the exact pending identity match`() = runTest {
        val collection = MediaStoreExportDocumentStore.downloadsCollection()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val rows = cursor(
            projection,
            arrayOf(42L, request.displayName, request.mimeType),
            arrayOf(43L, ExportNaming.request("stream:1:song-2:auto:auto", "Track", "flac").displayName, "audio/flac"),
            arrayOf(44L, "Unrelated.flac", "audio/flac"),
        )
        val resolver = mockk<ContentResolver> {
            every {
                query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1",
                    arrayOf(ExportNaming.RELATIVE_PATH),
                    null,
                )
            } returns rows
        }

        val matches = store(resolver).pendingMatches("stream:1:song-1:auto:auto", "Track")

        assertThat(matches).containsExactly(
            PendingExportDocument(ref, request.displayName, request.mimeType),
        )
    }

    @Test
    fun `insert returns only an owned downloads reference`() = runTest {
        val resolver = mockk<ContentResolver> {
            every { insert(MediaStoreExportDocumentStore.downloadsCollection(), any()) } returns uri
        }

        assertThat(store(resolver).insertPending(request)).isEqualTo(ref)
        verify(exactly = 1) {
            resolver.insert(
                MediaStoreExportDocumentStore.downloadsCollection(),
                match { values ->
                    values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1 &&
                        values.getAsInteger(MediaStore.Audio.Media.IS_MUSIC) == 0 &&
                        values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == ExportNaming.RELATIVE_PATH &&
                        values.getAsString(MediaStore.MediaColumns.MIME_TYPE) == request.mimeType &&
                        values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == request.displayName
                },
            )
        }
    }

    private fun store(resolver: ContentResolver) = MediaStoreExportDocumentStore(resolver, silentTestLogger)

    private fun source(bytes: ByteArray): File = File(temp.root, "source.part").apply { writeBytes(bytes) }

    private fun cursor(columns: Array<String>, vararg rows: Array<Any?>): MatrixCursor =
        MatrixCursor(columns).apply { rows.forEach(::addRow) }
}
