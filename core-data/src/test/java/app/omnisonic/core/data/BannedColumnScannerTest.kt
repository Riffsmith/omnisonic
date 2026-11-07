package app.omnisonic.core.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fails the build when an obsolete pre-release column label (the FIX-15
 * rename map) appears anywhere in the shipped source, SQL, exported schema or
 * documentation.
 *
 * The banned list is not committed: like the studied-app name pattern it is
 * stored in local Git configuration (`omnisonic.bannedColumns`) with an
 * `OMNISONIC_BANNED_COLUMNS` environment override for CI. A hit is reported as
 * `path:line` only, so the obsolete labels do not leak back into logs.
 */
class BannedColumnScannerTest {

    @Test
    fun `no obsolete column label appears in the repository`() {
        val pattern = bannedColumnPattern()
        val hits = scan(repositoryRoot(), pattern)

        if (hits.isNotEmpty()) {
            fail(
                "obsolete pre-release column label found (configured by " +
                    "omnisonic.bannedColumns / OMNISONIC_BANNED_COLUMNS):\n" +
                    hits.joinToString("\n"),
            )
        }
    }

    @Test
    fun `scanner reports a hit as path and line only`() {
        val file = File.createTempFile("banned-column-scan", ".kt")
        try {
            file.writeText("val clean = 1\nval BannedColumnFixtureToken = 2\n")
            val hits = scan(file, Regex("BannedColumnFixtureToken"))

            assertThat(hits).hasSize(1)
            assertThat(hits.single()).endsWith(":2")
            assertThat(hits.single()).doesNotContain("BannedColumnFixtureToken")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `scanner accepts a file without a banned column label`() {
        val file = File.createTempFile("banned-column-clean", ".kt")
        try {
            file.writeText("val latinTitle: String? = null\nval position: Int = 0\n")
            assertThat(scan(file, Regex("BannedColumnFixtureToken"))).isEmpty()
        } finally {
            file.delete()
        }
    }

    private fun bannedColumnPattern(): Regex {
        val configured = System.getenv("OMNISONIC_BANNED_COLUMNS")
            ?.takeIf { it.isNotBlank() }
            ?: runGitConfig("omnisonic.bannedColumns")
            ?: throw AssertionError(
                "no banned-column list configured; run once outside the repo:\n" +
                    "  git config --local omnisonic.bannedColumns '<regex1>|<regex2>'\n" +
                    "or set OMNISONIC_BANNED_COLUMNS in CI",
            )
        return Regex(configured)
    }

    private fun runGitConfig(key: String): String? =
        runCatching {
            val process = ProcessBuilder("git", "config", "--get", key)
                .directory(repositoryRoot())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotEmpty()) output else null
        }.getOrNull()

    private fun scan(root: File, pattern: Regex): List<String> {
        if (root.isFile) return hitsIn(root, pattern)
        return root.walkTopDown()
            .filter { it.isFile && it.extension in SCANNED_EXTENSIONS }
            .filterNot { file -> ignoredSegments.any { segment -> file.relativeTo(root).invariantSeparatorsPath.split('/').contains(segment) } }
            .sortedBy { it.path }
            .flatMap { hitsIn(it, pattern).asSequence() }
            .toList()
    }

    private fun hitsIn(file: File, pattern: Regex): List<String> =
        buildList {
            file.readLines().forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line)) add("${file.path}:${index + 1}")
            }
        }

    private fun repositoryRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("repository root not found from ${File("").absolutePath}")
    }

    private companion object {
        val SCANNED_EXTENSIONS = setOf("kt", "kts", "json", "md", "sql", "sh", "xml", "toml")
        val ignoredSegments = setOf(
            ".git", ".gradle", ".agents", "build", "reference", "dex", "progress",
        )
    }
}
