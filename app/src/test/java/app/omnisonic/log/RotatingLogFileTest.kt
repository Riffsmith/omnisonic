package app.omnisonic.log

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RotatingLogFileTest {

    @TempDir
    lateinit var tempDir: Path

    private fun file(maxBytes: Long = 1_000_000, maxFiles: Int = 3): RotatingLogFile =
        RotatingLogFile(tempDir.toFile(), "runtime", maxBytes = maxBytes, maxFiles = maxFiles)

    @Test
    fun rotatesAtTheSizeCapAndKeepsAtMostThreeFiles() {
        val log = file(maxBytes = 100)

        repeat(20) { index -> log.append("line-$index-${"x".repeat(30)}\n") }

        val files = log.existingFiles()
        assertThat(files).hasSize(3)
        assertThat(files.map { it.name })
            .containsExactly("runtime.log", "runtime.1", "runtime.2")
        assertThat(files.sumOf { it.length() }).isAtMost(300L)
    }

    @Test
    fun oldestFileIsDeleted() {
        val log = file(maxBytes = 100)

        repeat(20) { index -> log.append("line-$index-${"x".repeat(30)}\n") }

        val content = log.existingFiles().joinToString("") { it.readText() }
        assertThat(content).doesNotContain("line-0-")
        assertThat(content).contains("line-19-")
    }

    @Test
    fun appendsKeepWholeLinesTogether() {
        val log = file()
        val expected = (0 until 100).map { "entry-$it" }

        expected.forEach { log.append("$it\n") }

        assertThat(log.currentFile.readLines()).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun concurrentAppendsDoNotCorruptLines() {
        val log = file()
        val executors = Executors.newFixedThreadPool(4)
        val perThread = 100

        repeat(4) { thread ->
            executors.submit {
                repeat(perThread) { index -> log.append("t$thread-$index\n") }
            }
        }
        executors.shutdown()
        assertThat(executors.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

        val lines = log.currentFile.readLines()
        assertThat(lines).hasSize(4 * perThread)
        assertThat(lines.filter { Regex("""t\d-\d+""").matches(it) }).hasSize(lines.size)
    }

    @Test
    fun unwritableDirectoryReturnsFalseInsteadOfThrowing() {
        val directoryPath = File(tempDir.toFile(), "not-a-directory")
        directoryPath.writeText("occupied")
        val log = RotatingLogFile(directoryPath, "runtime")

        assertThat(log.append("boom\n")).isFalse()
    }
}
