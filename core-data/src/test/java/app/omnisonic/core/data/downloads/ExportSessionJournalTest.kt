package app.omnisonic.core.data.downloads

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExportSessionJournalTest {

    @TempDir
    lateinit var tempDir: File

    private val mediaKey = "stream:1:song-1:auto:auto"
    private val uri = "content://media/external/downloads/7"

    private fun journal() = ExportSessionJournal(File(tempDir, "sessions"))

    private fun session(
        phase: ExportSessionPhase = ExportSessionPhase.PENDING,
        uri: String = this.uri,
        mediaKey: String = this.mediaKey,
        expectedBytes: Long = 2_048,
    ) = ExportSession(
        mediaKey = mediaKey,
        uri = uri,
        displayName = "Track [0123abcd].flac",
        mimeType = "audio/flac",
        expectedBytes = expectedBytes,
        phase = phase,
    )

    @Test
    fun `a session round-trips through one deterministic file per stream key`() {
        val journal = journal()

        assertTrue(journal.write(session()))

        val read = journal.read(mediaKey)
        assertThat(read).isEqualTo(session())
        assertThat(journal.sessionFile(mediaKey).name)
            .isEqualTo("${OfflinePathRules.sha256Hex(mediaKey)}.export")
        assertThat(journal.sessionFile(mediaKey).parentFile).isEqualTo(File(tempDir, "sessions"))
        assertThat(File(tempDir, "sessions").listFiles()!!.map { it.name })
            .containsExactly(journal.sessionFile(mediaKey).name)
    }

    @Test
    fun `rewriting a session replaces the phase atomically without a temp leftover`() {
        val journal = journal()
        journal.write(session())

        assertTrue(journal.write(session(phase = ExportSessionPhase.PUBLISHED)))

        assertThat(journal.read(mediaKey)!!.phase).isEqualTo(ExportSessionPhase.PUBLISHED)
        assertThat(File(tempDir, "sessions").listFiles()!!.map { it.name })
            .containsExactly(journal.sessionFile(mediaKey).name)
    }

    @Test
    fun `deleting removes only that key's session`() {
        val journal = journal()
        journal.write(session())
        journal.write(session(mediaKey = "stream:1:song-2:auto:auto"))

        journal.delete(mediaKey)

        assertThat(journal.read(mediaKey)).isNull()
        assertThat(journal.read("stream:1:song-2:auto:auto")).isNotNull()
    }

    @Test
    fun `a foreign or malformed uri is refused so recovery cannot touch another document`() {
        val journal = journal()

        assertFalse(journal.write(session(uri = "content://media/external/images/media/7")))
        assertFalse(journal.write(session(uri = "file:///data/music.flac")))
        assertFalse(journal.write(session(uri = "https://host.example/track.flac")))
        assertThat(journal.read(mediaKey)).isNull()
    }

    @Test
    fun `non-positive sizes and control characters are refused`() {
        val journal = journal()

        assertFalse(journal.write(session(expectedBytes = 0)))
        assertFalse(journal.write(session(mediaKey = "stream:1:bad\u0000key:auto:auto")))
        assertFalse(
            journal.write(
                session().copy(displayName = "bad\u0001name"),
            ),
        )
        assertThat(journal.read(mediaKey)).isNull()
    }

    @Test
    fun `a corrupt file is listed as unreadable and never decoded`() {
        val journal = journal()
        journal.write(session())
        val file = journal.sessionFile(mediaKey)
        file.writeText("v=2\nkey=x\n")

        assertThat(journal.read(mediaKey)).isNull()
        assertThat(journal.selectAll()).isEmpty()
        assertThat(journal.unreadable()).containsExactly(file)
    }

    @Test
    fun `selectAll returns every decodable session and skips temp files`() {
        val journal = journal()
        journal.write(session())
        journal.write(session(mediaKey = "stream:1:song-2:auto:auto"))
        File(File(tempDir, "sessions"), "stray.export.tmp").writeText("v=1\n")

        val all = journal.selectAll()

        assertThat(all.map { it.mediaKey })
            .containsExactly(mediaKey, "stream:1:song-2:auto:auto")
        assertThat(journal.unreadable()).isEmpty()
    }

    @Test
    fun `a missing ledger directory reads empty rather than failing`() {
        val journal = journal()

        assertThat(journal.read(mediaKey)).isNull()
        assertThat(journal.selectAll()).isEmpty()
        assertThat(journal.unreadable()).isEmpty()
    }
}
