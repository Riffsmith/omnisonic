package app.omnisonic.core.data.downloads

import android.provider.MediaStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportNamingTest {

    private fun request(title: String?, extension: String = "flac", key: String = "stream:1:song-1:auto:auto") =
        ExportNaming.request(key, title, extension)

    @Test
    fun `the public path is one fixed Downloads subtree`() {
        assertThat(ExportNaming.RELATIVE_PATH).isEqualTo("Download/Omnisonic/")
        assertThat(ExportNaming.isOwnedRelativePath("Download/Omnisonic/")).isTrue()
        assertThat(ExportNaming.isOwnedRelativePath("Download/Omnisonic")).isTrue()
        assertThat(ExportNaming.isOwnedRelativePath("Download/Other/")).isFalse()
        assertThat(ExportNaming.isOwnedRelativePath("Music/Omnisonic/")).isFalse()
        assertThat(ExportNaming.isOwnedRelativePath(null)).isFalse()
        assertThat(request("Track").relativePath).isEqualTo("Download/Omnisonic/")
    }

    @Test
    fun `path separators colons control characters and url syntax are stripped from the name`() {
        val name = ExportNaming.baseName("AC/DC: Live? \"Best\" <of> | \\ mix *")

        assertThat(name).isEqualTo("AC DC Live Best of mix")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain(":")
        assertThat(name).doesNotContain("?")
    }

    @Test
    fun `no provider url or signed parameter reaches the public values`() {
        val plain = request("Track")

        assertThat(plain.relativePath).isEqualTo(ExportNaming.RELATIVE_PATH)
        assertThat(plain.displayName).doesNotContain("http")
        assertThat(plain.displayName).doesNotContain("stream:")
        assertThat(plain.displayName).doesNotContain("token=")
        assertThat(plain.displayName).doesNotContain("/")
        assertThat(plain.mimeType).isEqualTo("audio/flac")

        val name = ExportNaming.displayName(
            title = "Track https://host.example/stream?token=SECRET&exp=1",
            mediaKey = "stream:1:song-1:auto:auto",
            extension = "mp3",
        )

        assertThat(name).doesNotContain("://")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("?")
        assertThat(name).doesNotContain(":")
        assertThat(name).endsWith(".mp3")
    }

    @Test
    fun `blank control only dot names and windows reserved names get the fixed fallback`() {
        assertThat(ExportNaming.baseName(null)).isEqualTo(ExportNaming.FALLBACK_BASE_NAME)
        assertThat(ExportNaming.baseName("   ")).isEqualTo(ExportNaming.FALLBACK_BASE_NAME)
        assertThat(ExportNaming.baseName("\u0000\u0007")).isEqualTo(ExportNaming.FALLBACK_BASE_NAME)
        assertThat(ExportNaming.baseName("...")).isEqualTo(ExportNaming.FALLBACK_BASE_NAME)
        assertThat(ExportNaming.baseName("con")).isEqualTo(ExportNaming.FALLBACK_BASE_NAME)
        assertThat(ExportNaming.baseName("LPT1")).isEqualTo(ExportNaming.FALLBACK_BASE_NAME)
    }

    @Test
    fun `names are bounded without splitting a surrogate pair`() {
        val long = "A".repeat(ExportNaming.MAX_BASE_LENGTH + 40) + "\uD83C\uDFB5"
        val name = ExportNaming.baseName(long)

        assertThat(name.length).isAtMost(ExportNaming.MAX_BASE_LENGTH)
        assertThat(name).isEqualTo("A".repeat(ExportNaming.MAX_BASE_LENGTH))

        val emojiTail = "B".repeat(ExportNaming.MAX_BASE_LENGTH - 1) + "\uD83C\uDFB5"
        assertThat(ExportNaming.baseName(emojiTail)).isEqualTo("B".repeat(ExportNaming.MAX_BASE_LENGTH - 1))
    }

    @Test
    fun `the same title on different identities never collides while one identity is stable`() {
        val first = ExportNaming.displayName("Track", "stream:1:song-1:auto:auto", "flac")
        val second = ExportNaming.displayName("Track", "stream:1:song-2:auto:auto", "flac")
        val repeat = ExportNaming.displayName("Track", "stream:1:song-1:auto:auto", "flac")

        assertThat(first).isNotEqualTo(second)
        assertThat(first).isEqualTo(repeat)
        assertThat(first).isEqualTo("Track [${ExportNaming.identityToken("stream:1:song-1:auto:auto")}].flac")
        assertThat(ExportNaming.identityToken("stream:1:song-1:auto:auto")).matches("[0-9a-f]{8}")
    }

    @Test
    fun `MIME follows the extension and falls back for unknown or unsafe values`() {
        assertThat(ExportNaming.mimeType("flac")).isEqualTo("audio/flac")
        assertThat(ExportNaming.mimeType("MP3")).isEqualTo("audio/mpeg")
        assertThat(ExportNaming.mimeType("m4a")).isEqualTo("audio/mp4")
        assertThat(ExportNaming.mimeType("ogg")).isEqualTo("audio/ogg")
        assertThat(ExportNaming.mimeType("wav")).isEqualTo("audio/x-wav")
        assertThat(ExportNaming.mimeType("bin")).isEqualTo(ExportNaming.MIME_FALLBACK)
        assertThat(ExportNaming.mimeType("../flac")).isEqualTo(ExportNaming.MIME_FALLBACK)
        assertThat(ExportNaming.mimeType(null)).isEqualTo(ExportNaming.MIME_FALLBACK)
    }

    @Test
    fun `an unsafe extension falls back to the stored bin extension`() {
        assertThat(request("Track", extension = "../etc/passwd").displayName).endsWith(".bin")
        assertThat(request("Track", extension = "").displayName).endsWith(".bin")
        assertThat(request("Track", extension = "flac").displayName).endsWith(".flac")
    }

    @Test
    fun `pending values carry the display name MIME relative path pending flag and no music flag`() {
        val values = MediaStoreExportDocumentStore.pendingValues(request("Track"))

        assertThat(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            .isEqualTo("Track [${ExportNaming.identityToken("stream:1:song-1:auto:auto")}].flac")
        assertThat(values.getAsString(MediaStore.MediaColumns.MIME_TYPE)).isEqualTo("audio/flac")
        assertThat(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)).isEqualTo("Download/Omnisonic/")
        assertThat(values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)).isEqualTo(1)
        assertThat(values.getAsInteger(MediaStore.Audio.Media.IS_MUSIC)).isEqualTo(0)
        assertThat(values.keySet()).containsExactly(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.Audio.Media.IS_MUSIC,
        )
    }

    @Test
    fun `only this app's downloads collection URIs are owned`() {
        assertThat(isOwnedExportDocumentUri("content://media/external/downloads/42")).isTrue()
        assertThat(isOwnedExportDocumentUri("content://media/external_primary/downloads/42")).isTrue()
        assertThat(isOwnedExportDocumentUri("content://media/external/downloads/0")).isFalse()
        assertThat(isOwnedExportDocumentUri("content://media/external/images/media/42")).isFalse()
        assertThat(isOwnedExportDocumentUri("content://media/external/downloads/42?x=1")).isFalse()
        assertThat(isOwnedExportDocumentUri("content://other/downloads/42")).isFalse()
        assertThat(isOwnedExportDocumentUri("file:///storage/emulated/0/Download/Omnisonic/a.flac")).isFalse()
        assertThat(isOwnedExportDocumentUri("content://media/external/downloads/abc")).isFalse()
        assertThat(isOwnedExportDocumentUri(null)).isFalse()
    }

    @Test
    fun `the downloads collection is the primary external volume`() {
        assertThat(MediaStoreExportDocumentStore.downloadsCollection().toString())
            .isEqualTo("content://media/external_primary/downloads")
        assertThat(MediaStoreExportDocumentStore.TRUNCATING_WRITE).isEqualTo("wt")
    }
}
