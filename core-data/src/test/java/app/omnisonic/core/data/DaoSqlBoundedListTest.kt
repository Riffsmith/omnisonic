package app.omnisonic.core.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * Android 10 ships SQLite 3.22, which rejects statements bound with more than
 * 999 variables (SQLITE_MAX_VARIABLE_NUMBER). Every parameterized IN / NOT IN
 * list in DAO SQL must therefore be fed in batches: the raw single-batch query
 * is named `...Chunk` and a plain-named default method feeds it batches of
 * [SQL_IN_CHUNK_SIZE] elements.
 */
class DaoSqlBoundedListTest {

    @Test
    fun `dao sources bind parameterized in-lists only from chunk methods`() {
        val directory = daoSourceDirectory()
        val violations = directory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { violationsIn(it).asSequence() }
            .toList()

        if (violations.isNotEmpty()) {
            fail(
                "Parameterized IN lists above SQLite 3.22's 999-variable limit:\n" +
                    violations.joinToString("\n"),
            )
        }
        assertThat(directory.listFiles()?.size ?: 0).isGreaterThan(0)
    }

    @Test
    fun `scanner rejects an in-list on a method without the chunk suffix`() {
        val file = tempDaoSource(
            """
            @Query("DELETE FROM songs WHERE id IN (:ids)")
            suspend fun deleteByIds(ids: List<Long>)
            """.trimIndent(),
        )
        try {
            assertThat(violationsIn(file)).isNotEmpty()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `scanner accepts an in-list on a chunk-suffixed method`() {
        val file = tempDaoSource(
            """
            @Query("DELETE FROM songs WHERE id IN (:ids)")
            suspend fun deleteByIdsChunk(ids: List<Long>)
            """.trimIndent(),
        )
        try {
            assertThat(violationsIn(file)).isEmpty()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `scanner rejects a not-in list on a method without the chunk suffix`() {
        val file = tempDaoSource(
            """
            @Query(
                '''
                UPDATE songs SET missingOnServer = 1
                WHERE externalId NOT IN (:seenExternalIds)
                ''',
            )
            suspend fun tombstoneUnseen(seenExternalIds: List<String>)
            """.trimIndent(),
        )
        try {
            assertThat(violationsIn(file)).isNotEmpty()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `scanner ignores in-subqueries without bound parameters`() {
        val file = tempDaoSource(
            """
            @Query(
                '''
                SELECT id FROM albums
                WHERE id NOT IN (
                    SELECT DISTINCT albumId FROM songs WHERE albumId IS NOT NULL
                )
                ''',
            )
            suspend fun selectUnseenIds(): List<Long>
            """.trimIndent(),
        )
        try {
            assertThat(violationsIn(file)).isEmpty()
        } finally {
            file.delete()
        }
    }

    private fun violationsIn(file: File): List<String> {
        val boundInList = Regex("""\bIN\s*\(\s*:""", RegexOption.IGNORE_CASE)
        val funDeclaration = Regex("""\bfun\s+(\w+)""")
        val text = file.readText()
        return buildList {
            boundInList.findAll(text).forEach { match ->
                val method = funDeclaration.find(text, match.range.last + 1)?.groupValues?.get(1)
                    ?: "<unknown>"
                if (!method.endsWith("Chunk")) {
                    val line = text.take(match.range.first).count { it == '\n' } + 1
                    add("${file.name}:$line: IN-list in $method")
                }
            }
        }
    }

    private fun tempDaoSource(content: String): File =
        File.createTempFile("dao-bound-lists", ".kt").apply { writeText(content) }

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
