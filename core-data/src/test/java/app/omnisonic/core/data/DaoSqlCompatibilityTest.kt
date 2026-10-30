package app.omnisonic.core.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class DaoSqlCompatibilityTest {

    @Test
    fun `dao sources stay within API 29 SQLite syntax`() {
        val directory = daoSourceDirectory()
        val violations = directory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { violationsIn(it).asSequence() }
            .toList()

        if (violations.isNotEmpty()) {
            fail("SQL needs a newer SQLite than Android 10/API 29 provides:\n" + violations.joinToString("\n"))
        }
        assertThat(directory.listFiles()?.size ?: 0).isGreaterThan(0)
    }

    @Test
    fun `scanner rejects upsert syntax`() {
        val file = File.createTempFile("dao-scan", ".kt")
        try {
            file.writeText("SELECT * FROM t WHERE id = 1 ON CONFLICT(id) DO UPDATE SET x = 1")
            assertThat(violationsIn(file)).isNotEmpty()
        } finally {
            file.delete()
        }
    }

    private fun violationsIn(file: File): List<String> {
        val forbidden = listOf(
            Regex("""\bON\s+CONFLICT\b""", RegexOption.IGNORE_CASE),
            Regex("""\bRETURNING\b""", RegexOption.IGNORE_CASE),
            Regex("""\bOVER\s*\(""", RegexOption.IGNORE_CASE),
        )
        return buildList {
            file.readLines().forEachIndexed { index, line ->
                forbidden.firstOrNull { it.containsMatchIn(line) }?.let { pattern ->
                    add("${file.name}:${index + 1}: ${pattern.pattern} in $line")
                }
            }
        }
    }

    private fun daoSourceDirectory(): File {
        val relative = "src/main/java/app/omnisonic/core/data/db/dao"
        val candidates = listOf(
            File(relative),
            File("core-data/$relative"),
            File("../core-data/$relative"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("DAO source directory not found from ${File(".").absolutePath}")
    }
}
