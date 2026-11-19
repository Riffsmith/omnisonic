package app.omnisonic.core.data.export

import android.content.ContentResolver
import android.net.Uri
import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.backup.SafBackupDocumentSink
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayEventCsvExporterTest : DatabaseTestBase() {

    private fun exporter() = PlayEventCsvExporter(db)

    private suspend fun exportToBytes(includeBom: Boolean = false): Pair<Int, ByteArray> {
        val out = ByteArrayOutputStream()
        val count = exporter().writeCsv(out, includeBom)
        return count to out.toByteArray()
    }

    private suspend fun exportToString(includeBom: Boolean = false): Pair<Int, String> {
        val (count, bytes) = exportToBytes(includeBom)
        return count to bytes.toString(Charsets.UTF_8)
    }

    @Test
    fun `header is the first record with the pinned column order and crlf`() = runDbTest {
        newProvider(id = 1)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_700_000_000_000, durationMs = 200_000)

        val (count, csv) = exportToString()

        assertThat(count).isEqualTo(1)
        assertThat(csv).startsWith("providerId,externalId,playedAtUtc,playedAtMs,played,skipped,durationMs\r\n")
        assertThat(csv).doesNotContain("\uFEFF")
    }

    @Test
    fun `rows carry provider external id iso utc epoch flags and duration ordered by provider then played at`() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        playEventDao.recordPlay(2, "b", playedAtMs = 3_000, durationMs = 30_000)
        playEventDao.recordSkip(1, "a", playedAtMs = 2_000, durationMs = 10_000)
        playEventDao.recordPlay(1, "a", playedAtMs = 1_000, durationMs = 20_000)

        val (count, csv) = exportToString()

        assertThat(count).isEqualTo(3)
        val lines = csv.split("\r\n")
        assertThat(lines[0]).isEqualTo("providerId,externalId,playedAtUtc,playedAtMs,played,skipped,durationMs")
        assertThat(lines[1]).isEqualTo("1,a,1970-01-01T00:00:01Z,1000,true,false,20000")
        assertThat(lines[2]).isEqualTo("1,a,1970-01-01T00:00:02Z,2000,false,true,10000")
        assertThat(lines[3]).isEqualTo("2,b,1970-01-01T00:00:03Z,3000,true,false,30000")
        assertThat(lines[4]).isEmpty()
    }

    @Test
    fun `commas quotes and newlines in the external id are rfc 4180 escaped`() = runDbTest {
        newProvider(id = 1)
        playEventDao.recordPlay(1, "a,b\"c\nd", playedAtMs = 1_000, durationMs = 5)

        val (count, csv) = exportToString()

        assertThat(count).isEqualTo(1)
        assertThat(csv).isEqualTo(
            "providerId,externalId,playedAtUtc,playedAtMs,played,skipped,durationMs\r\n" +
                "1,\"a,b\"\"c\nd\",1970-01-01T00:00:01Z,1000,true,false,5\r\n",
        )
    }

    @Test
    fun `empty export writes only the header record`() = runDbTest {
        val (count, csv) = exportToString()

        assertThat(count).isEqualTo(0)
        assertThat(csv).isEqualTo("providerId,externalId,playedAtUtc,playedAtMs,played,skipped,durationMs\r\n")
    }

    @Test
    fun `utf 8 bom is written only when requested`() = runDbTest {
        newProvider(id = 1)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 1)

        val (_, withoutBom) = exportToBytes(includeBom = false)
        val (count, withBom) = exportToBytes(includeBom = true)

        assertThat(count).isEqualTo(1)
        assertThat(withoutBom[0].toInt() and 0xFF).isNotEqualTo(0xEF)
        assertThat(withBom[0].toInt() and 0xFF).isEqualTo(0xEF)
        assertThat(withBom[1].toInt() and 0xFF).isEqualTo(0xBB)
        assertThat(withBom[2].toInt() and 0xFF).isEqualTo(0xBF)
        assertThat(withBom.drop(3).toByteArray()).isEqualTo(withoutBom)
    }

    @Test
    fun `saf document sink receives the csv through the content resolver`() = runDbTest {
        newProvider(id = 1)
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 100)
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Ahistory.csv")
        val bytes = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver> {
            every { openOutputStream(uri, "wt") } returns bytes
        }

        val count = exporter().writeCsv(SafBackupDocumentSink(resolver, uri))

        verify(exactly = 1) { resolver.openOutputStream(uri, "wt") }
        assertThat(count).isEqualTo(1)
        val text = bytes.toString(Charsets.UTF_8)
        assertThat(text).startsWith("providerId,externalId,playedAtUtc")
        assertThat(text).contains("1,song-1,1970-01-01T00:00:01Z,1000,true,false,100\r\n")
    }
}
