package app.omnisonic.core.data.downloads

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OfflinePathRulesTest {

    @TempDir
    lateinit var tempDir: File

    private fun identity(
        path: String = "/music/Artist/Album/01 - Song.flac",
        providerId: Long = 7,
        bitrate: Int? = null,
        format: String? = null,
    ) = OfflineMediaIdentity(providerId, path, bitrate, format)

    private fun planned(identity: OfflineMediaIdentity): OfflinePathPlan {
        val result = OfflinePathRules.plan(identity)
        assertThat(result).isInstanceOf(OfflinePathResult.Planned::class.java)
        return (result as OfflinePathResult.Planned).plan
    }

    @Test
    fun `the same identity always plans the same path`() {
        val first = planned(identity())
        val second = planned(identity())

        assertThat(first).isEqualTo(second)
        assertThat(first.relativePath).matches("OfflineMedias/[0-9a-f]/[0-9a-f]{64}\\.flac")
        assertThat(first.relativePath).startsWith("OfflineMedias/${first.hash.first()}/")
        assertThat(first.relativePartPath).isEqualTo(
            "OfflineMedias/${first.hash.first()}/${first.hash}.part",
        )
    }

    @Test
    fun `the same provider path on different providers cannot share a file`() {
        val first = planned(identity(providerId = 1))
        val second = planned(identity(providerId = 2))

        assertThat(first.relativePath).isNotEqualTo(second.relativePath)
        assertThat(first.relativePartPath).isNotEqualTo(second.relativePartPath)
    }

    @Test
    fun `bitrate and format variants are distinct paths`() {
        val plain = planned(identity())
        val bitrate = planned(identity(bitrate = 192))
        val format = planned(identity(format = "mp3"))
        val both = planned(identity(bitrate = 192, format = "mp3"))

        assertThat(
            setOf(
                plain.relativePath,
                bitrate.relativePath,
                format.relativePath,
                both.relativePath,
            ),
        ).hasSize(4)
    }

    @Test
    fun `unicode paths are normalized and remain valid file names`() {
        val composed = planned(identity(path = "/music/caf\u00e9.flac"))
        val decomposed = planned(identity(path = "/music/cafe\u0301.flac"))
        val emoji = planned(identity(path = "/music/\u65e5\u672c\u8a9e/\ud83c\udfb5.flac"))

        assertThat(composed.relativePath).isEqualTo(decomposed.relativePath)
        assertThat(OfflinePathRules.isSafeRelativePath(emoji.relativePath)).isTrue()
    }

    @Test
    fun `a very long path still produces a short deterministic file name`() {
        val longPath = "/music/" + "segment/".repeat(450) + "track.flac"

        val plan = planned(identity(path = longPath))

        assertThat(plan.relativePath.length).isLessThan(128)
        assertThat(OfflinePathRules.isSafeRelativePath(plan.relativePath)).isTrue()
    }

    @Test
    fun `colon containing ids are accepted`() {
        val plan = planned(identity(path = "provider:123:track:abc"))

        assertThat(plan.relativePath).matches("OfflineMedias/[0-9a-f]/[0-9a-f]{64}\\.bin")
    }

    @Test
    fun `an injected hash collision is refused once the path is assigned`() {
        val first = identity(path = "/music/a.flac")
        val second = identity(path = "/music/b.flac")
        val hasher: (String) -> String = { "collision" }

        val firstPlan = OfflinePathRules.plan(first, hasher = hasher)
        assertThat(firstPlan).isInstanceOf(OfflinePathResult.Planned::class.java)
        val relativePath = (firstPlan as OfflinePathResult.Planned).plan.relativePath

        val secondPlan = OfflinePathRules.plan(
            identity = second,
            assignedPaths = mapOf(relativePath to OfflinePathRules.identityKey(first)),
            hasher = hasher,
        )

        assertThat(secondPlan).isInstanceOf(OfflinePathResult.Collision::class.java)
        assertThat((secondPlan as OfflinePathResult.Collision).existingIdentityKey)
            .isEqualTo(OfflinePathRules.identityKey(first))
    }

    @Test
    fun `an unusable injected hash is rejected`() {
        val result = OfflinePathRules.plan(identity(), hasher = { "../escape" })

        assertThat(result).isInstanceOf(OfflinePathResult.InvalidIdentity::class.java)
    }

    @Test
    fun `invalid identities are rejected`() {
        assertThat(OfflinePathRules.plan(identity(providerId = 0)))
            .isInstanceOf(OfflinePathResult.InvalidIdentity::class.java)
        assertThat(OfflinePathRules.plan(identity(path = "  ")))
            .isInstanceOf(OfflinePathResult.InvalidIdentity::class.java)
        assertThat(OfflinePathRules.plan(identity(path = "bad\u0000path")))
            .isInstanceOf(OfflinePathResult.InvalidIdentity::class.java)
        assertThat(OfflinePathRules.plan(identity(bitrate = 0)))
            .isInstanceOf(OfflinePathResult.InvalidIdentity::class.java)
        assertThat(OfflinePathRules.plan(identity(format = "  ")))
            .isInstanceOf(OfflinePathResult.InvalidIdentity::class.java)
    }

    @Test
    fun `explicit transcode formats choose the extension`() {
        assertThat(OfflinePathRules.resolveExtension("mp3", "/music/a.flac")).isEqualTo("mp3")
        assertThat(OfflinePathRules.resolveExtension("FLAC", "/music/a.mp3")).isEqualTo("flac")
        assertThat(OfflinePathRules.resolveExtension("opus", null)).isEqualTo("opus")
        assertThat(OfflinePathRules.resolveExtension("mpeg", null)).isEqualTo("mp3")
        assertThat(OfflinePathRules.resolveExtension("mp4", null)).isEqualTo("m4a")
        assertThat(OfflinePathRules.resolveExtension("vorbis", null)).isEqualTo("ogg")
    }

    @Test
    fun `source extensions cover missing, trailing, double and query forms`() {
        assertThat(OfflinePathRules.resolveExtension(null, "/music/a.FLAC")).isEqualTo("flac")
        assertThat(OfflinePathRules.resolveExtension(null, "/music/a.flac?token=abc"))
            .isEqualTo("flac")
        assertThat(OfflinePathRules.resolveExtension(null, "/music/a.mp3#fragment"))
            .isEqualTo("mp3")
        assertThat(OfflinePathRules.resolveExtension(null, "/music/a")).isEqualTo("bin")
        assertThat(OfflinePathRules.resolveExtension(null, "/music/a.")).isEqualTo("bin")
        assertThat(OfflinePathRules.resolveExtension(null, "/music/.hidden")).isEqualTo("bin")
        assertThat(OfflinePathRules.resolveExtension(null, "/music/a.tar.gz")).isEqualTo("bin")
        assertThat(OfflinePathRules.resolveExtension(null, "/music/a.flac.flac")).isEqualTo("flac")
    }

    @Test
    fun `passthrough and unknown formats fall back to the source then to bin`() {
        assertThat(OfflinePathRules.resolveExtension("raw", "/music/a.m4a")).isEqualTo("m4a")
        assertThat(OfflinePathRules.resolveExtension("auto", "/music/a.ogg")).isEqualTo("ogg")
        assertThat(OfflinePathRules.resolveExtension("unknown-format", "/music/a.wav"))
            .isEqualTo("wav")
        assertThat(OfflinePathRules.resolveExtension("unknown-format", "/music/a"))
            .isEqualTo("bin")
    }

    @Test
    fun `stored paths reject traversal absolute drive and control forms`() {
        assertThat(OfflinePathRules.isSafeRelativePath("OfflineMedias/a/file.flac")).isTrue()
        assertThat(OfflinePathRules.isSafeRelativePath("/etc/passwd")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("../escape.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("OfflineMedias/../escape.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("OfflineMedias//file.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("OfflineMedias/./file.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("OfflineMedias\\file.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("C:\\file.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("C:file.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("OfflineMedias/bad\u0001file.flac")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("")).isFalse()
        assertThat(OfflinePathRules.isSafeRelativePath("a".repeat(600))).isFalse()
    }

    @Test
    fun `resolution stays under the canonical root`() {
        val root = File(tempDir, "root").apply { mkdirs() }
        File(root, "OfflineMedias/a").mkdirs()
        val inside = File(root, "OfflineMedias/a/file.flac").apply { writeText("x") }

        assertThat(OfflinePathRules.resolveUnder(root, "OfflineMedias/a/file.flac"))
            .isEqualTo(inside.canonicalFile)
        assertThat(OfflinePathRules.resolveUnder(root, "../outside.flac")).isNull()
        assertThat(OfflinePathRules.resolveUnder(root, "/etc/passwd")).isNull()
    }

    @Test
    fun `a symlinked directory cannot escape the root`() {
        val root = File(tempDir, "root").apply { mkdirs() }
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(outside, "secret.flac").writeText("secret")
        val link = File(root, "link")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        assumeTrue(created.isSuccess)

        assertThat(OfflinePathRules.resolveUnder(root, "link/secret.flac")).isNull()
    }
}
