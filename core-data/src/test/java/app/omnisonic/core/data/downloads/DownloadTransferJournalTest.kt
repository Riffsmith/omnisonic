package app.omnisonic.core.data.downloads

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DownloadTransferJournalTest {

    @TempDir
    lateinit var tempDir: File

    private val journal = DownloadTransferJournal()
    private val identityHash = "a".repeat(64)

    private fun part(): File = File(tempDir, "track.part").apply {
        writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x04))
    }

    @Test
    fun `state round-trips beside the part`() {
        val part = part()
        val state = DownloadTransferState(
            identityHash = identityHash,
            strongEtag = "\"etag-1\"",
            lastModified = "Wed, 19 Nov 2026 10:00:00 GMT",
            totalBytes = 4_096,
        )

        assertThat(journal.write(part, state)).isTrue()

        val file = journal.stateFile(part)
        assertThat(file.name).isEqualTo("track.part.state")
        assertThat(file.parentFile).isEqualTo(part.parentFile)
        assertThat(journal.read(part)).isEqualTo(state)
        assertThat(journal.read(part)!!.ifRangeValidator()).isEqualTo("\"etag-1\"")
    }

    @Test
    fun `an empty validator falls back to last modified and then to none`() {
        val part = part()
        journal.write(
            part,
            DownloadTransferState(identityHash, strongEtag = null, lastModified = "Wed, 19 Nov 2026 10:00:00 GMT"),
        )
        assertThat(journal.read(part)!!.ifRangeValidator()).isEqualTo("Wed, 19 Nov 2026 10:00:00 GMT")

        journal.write(part, DownloadTransferState(identityHash))
        assertThat(journal.read(part)!!.ifRangeValidator()).isNull()
        assertThat(journal.read(part)!!.totalBytes).isNull()
    }

    @Test
    fun `weak etags are never persisted as validators`() {
        val part = part()

        assertThat(journal.write(part, DownloadTransferState(identityHash, strongEtag = "W/\"weak\""))).isFalse()

        assertThat(journal.stateFile(part).exists()).isFalse()
        assertThat(journal.read(part)).isNull()
    }

    @Test
    fun `corrupt or foreign state decodes to null`() {
        val part = part()
        assertThat(journal.write(part, DownloadTransferState(identityHash, totalBytes = 10))).isTrue()

        journal.stateFile(part).writeText("v=2\nidentity=$identityHash\n")
        assertThat(journal.read(part)).isNull()

        journal.stateFile(part).writeText("v=1\nidentity=not-a-hash\n")
        assertThat(journal.read(part)).isNull()

        journal.stateFile(part).writeText("v=1\nidentity=$identityHash\ntotal=-4\n")
        assertThat(journal.read(part)).isNull()

        journal.stateFile(part).writeText("v=1\nidentity=$identityHash\nidentity=$identityHash\n")
        assertThat(journal.read(part)).isNull()

        journal.stateFile(part).writeText("no separators here")
        assertThat(journal.read(part)).isNull()

        journal.stateFile(part).writeText("v=1\nidentity=$identityHash\ntotal=12\n")
        assertThat(journal.read(part)!!.totalBytes).isEqualTo(12)
    }

    @Test
    fun `the queue media key round-trips and a corrupt key is refused`() {
        val part = part()
        val key = "stream:1:disc:one:128:mp3"
        val state = DownloadTransferState(identityHash = identityHash, totalBytes = 4, mediaKey = key)

        assertThat(journal.write(part, state)).isTrue()
        assertThat(journal.read(part)).isEqualTo(state)

        journal.write(part, DownloadTransferState(identityHash, totalBytes = 4))
        assertThat(journal.read(part)!!.mediaKey).isNull()

        journal.stateFile(part).writeText("v=1\nidentity=$identityHash\ntotal=4\nkey=bad\u0001key\n")
        assertThat(journal.read(part)).isNull()
    }

    @Test
    fun `deleting the part state leaves the part untouched`() {
        val part = part()
        journal.write(part, DownloadTransferState(identityHash, totalBytes = 4))

        journal.delete(part)

        assertThat(journal.stateFile(part).exists()).isFalse()
        assertThat(part.exists()).isTrue()
        assertThat(journal.read(part)).isNull()
    }

    @Test
    fun `an over-long state file is refused instead of parsed`() {
        val part = part()
        journal.stateFile(part).writeText("v=1\n" + "x".repeat(8_192))

        assertThat(journal.read(part)).isNull()
    }
}
