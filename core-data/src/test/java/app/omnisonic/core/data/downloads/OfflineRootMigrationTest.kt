package app.omnisonic.core.data.downloads

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OfflineRootMigrationTest {

    @TempDir
    lateinit var tempDir: File

    private class RecordingGate : OfflineRootWorkGate {
        val events = mutableListOf<String>()

        override suspend fun pauseForRootChange() {
            events += "pause"
        }

        override suspend fun resumeAfterRootChange() {
            events += "resume"
        }
    }

    private inner class Fixture {
        val rootA = OfflineRoot("a", File(tempDir, "root-a").apply { mkdirs() })
        val rootB = OfflineRoot("b", File(tempDir, "root-b").apply { mkdirs() })
        val internal = OfflineRoot(OfflineRoot.INTERNAL_VOLUME, File(tempDir, "internal").apply { mkdirs() })
        val store = DownloadQueueSettingsStore.inMemory()
        val gate = RecordingGate()
        var rootList = listOf(rootA, rootB, internal)
        var freeBytes = OfflinePathRules.MIN_FREE_SPACE_BYTES * 10
        var writable = true
        val storage = OfflineStorage(
            roots = OfflineRootSource { rootList },
            settings = store,
            usableSpace = { freeBytes },
            writableProbe = { writable },
        )

        suspend fun pinA() {
            store.setRootSelection(OfflineRootSelection.Volume("a"))
            storage.prepare()
        }

        fun write(root: OfflineRoot, relative: String, content: String): File =
            File(storage.mediaDir(root), relative).apply {
                parentFile?.mkdirs()
                writeText(content)
            }

        fun read(root: OfflineRoot, relative: String): String =
            File(storage.mediaDir(root), relative).readText()
    }

    @Test
    fun `a successful move verifies files deletes the old copies and publishes the new root`(): Unit =
        runBlocking {
            val fixture = Fixture()
            fixture.pinA()
            fixture.write(fixture.rootA, "bucket/track.flac", "flac-bytes")
            fixture.write(fixture.rootA, "nested/deeper/track.mp3", "mp3-bytes")
            fixture.write(fixture.rootA, "bucket/track.flac.part", "partial")

            val result = fixture.storage.changeRoot(
                OfflineRootSelection.Volume("b"),
                fixture.gate,
            )

            assertThat(result).isEqualTo(
                OfflineRootChangeResult.Completed(
                    activeVolumeId = "b",
                    movedFiles = 3,
                    generation = 1,
                ),
            )
            assertThat(fixture.read(fixture.rootB, "bucket/track.flac")).isEqualTo("flac-bytes")
            assertThat(fixture.read(fixture.rootB, "nested/deeper/track.mp3")).isEqualTo("mp3-bytes")
            assertThat(fixture.read(fixture.rootB, "bucket/track.flac.part")).isEqualTo("partial")
            assertThat(fixture.storage.mediaDir(fixture.rootA).exists()).isFalse()
            assertThat(File(fixture.storage.mediaDir(fixture.rootB), ".nomedia").isFile).isTrue()
            assertThat(fixture.gate.events).containsExactly("pause", "resume").inOrder()

            val state = fixture.store.currentRootState()
            assertThat(state.activeVolumeId).isEqualTo("b")
            assertThat(state.generation).isEqualTo(1)
            assertThat(state.pendingVolumeId).isNull()
            assertThat(state.cleanupVolumeId).isNull()
        }

    @Test
    fun `selecting the active volume is a no-op without pausing workers`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "bytes")

        val result = fixture.storage.changeRoot(OfflineRootSelection.Volume("a"), fixture.gate)

        assertThat(result).isEqualTo(OfflineRootChangeResult.AlreadyActive)
        assertThat(fixture.gate.events).isEmpty()
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("bytes")
    }

    @Test
    fun `re-selecting the active root still verifies writability`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.writable = false

        val result = fixture.storage.changeRoot(OfflineRootSelection.Volume("a"), fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Failed(OfflineRootChangeFailure.NOT_WRITABLE),
        )
        assertThat(fixture.gate.events).isEmpty()
    }

    @Test
    fun `a destination conflict rolls back and keeps the old root authoritative`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "AAAA")
        fixture.write(fixture.rootB, "bucket/track.flac", "BBBB")

        val result = fixture.storage.changeRoot(OfflineRootSelection.Volume("b"), fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Failed(
                OfflineRootChangeFailure.DESTINATION_CONFLICT,
                "bucket/track.flac",
            ),
        )
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("AAAA")
        assertThat(fixture.read(fixture.rootB, "bucket/track.flac")).isEqualTo("BBBB")
        assertThat(fixture.gate.events).containsExactly("pause", "resume").inOrder()
        val state = fixture.store.currentRootState()
        assertThat(state.activeVolumeId).isEqualTo("a")
        assertThat(state.pendingVolumeId).isNull()
    }

    @Test
    fun `an existing identical destination file is verified and adopted`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "same-bytes")
        fixture.write(fixture.rootB, "bucket/track.flac", "same-bytes")

        val result = fixture.storage.changeRoot(OfflineRootSelection.Volume("b"), fixture.gate)

        assertThat(result).isInstanceOf(OfflineRootChangeResult.Completed::class.java)
        assertThat((result as OfflineRootChangeResult.Completed).movedFiles).isEqualTo(1)
        assertThat(fixture.storage.mediaDir(fixture.rootA).exists()).isFalse()
        assertThat(fixture.read(fixture.rootB, "bucket/track.flac")).isEqualTo("same-bytes")
    }

    @Test
    fun `an unavailable target fails before pausing workers`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "bytes")

        val result = fixture.storage.changeRoot(
            OfflineRootSelection.Volume("missing"),
            fixture.gate,
        )

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Failed(OfflineRootChangeFailure.TARGET_UNAVAILABLE, "missing"),
        )
        assertThat(fixture.gate.events).isEmpty()
        assertThat(fixture.store.currentRootState().activeVolumeId).isEqualTo("a")
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("bytes")
    }

    @Test
    fun `an unavailable source volume is never treated as empty`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "bytes")
        fixture.rootList = listOf(fixture.rootB, fixture.internal)

        val result = fixture.storage.changeRoot(OfflineRootSelection.Volume("b"), fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Failed(OfflineRootChangeFailure.SOURCE_UNAVAILABLE, "a"),
        )
        assertThat(fixture.gate.events).isEmpty()
        assertThat(fixture.store.currentRootState().activeVolumeId).isEqualTo("a")
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("bytes")
    }

    @Test
    fun `low space on the target leaves the old root untouched`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "bytes")
        fixture.freeBytes = OfflinePathRules.MIN_FREE_SPACE_BYTES - 1

        val result = fixture.storage.changeRoot(OfflineRootSelection.Volume("b"), fixture.gate)

        assertThat(result).isInstanceOf(OfflineRootChangeResult.Failed::class.java)
        assertThat((result as OfflineRootChangeResult.Failed).reason)
            .isEqualTo(OfflineRootChangeFailure.LOW_SPACE)
        assertThat(fixture.gate.events).containsExactly("pause", "resume").inOrder()
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("bytes")
        assertThat(fixture.store.currentRootState().pendingVolumeId).isNull()
        assertThat(fixture.store.currentRootState().activeVolumeId).isEqualTo("a")
    }

    @Test
    fun `an interrupted move resumes from the persisted pending journal`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "one")
        fixture.write(fixture.rootA, "bucket/other.mp3", "two")
        fixture.write(fixture.rootB, "bucket/track.flac", "one")
        fixture.store.beginRootMove(OfflineRootSelection.Volume("b"), "b", 1)

        val result = fixture.storage.recoverInterruptedRootChange(fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Completed(
                activeVolumeId = "b",
                movedFiles = 2,
                generation = 1,
            ),
        )
        assertThat(fixture.read(fixture.rootB, "bucket/track.flac")).isEqualTo("one")
        assertThat(fixture.read(fixture.rootB, "bucket/other.mp3")).isEqualTo("two")
        assertThat(fixture.storage.mediaDir(fixture.rootA).exists()).isFalse()
        assertThat(fixture.store.currentRootState().pendingVolumeId).isNull()
    }

    @Test
    fun `an interrupted move whose target vanished rolls back`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "bytes")
        fixture.store.beginRootMove(OfflineRootSelection.Volume("b"), "b", 1)
        fixture.rootList = listOf(fixture.rootA, fixture.internal)

        val result = fixture.storage.recoverInterruptedRootChange(fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Failed(OfflineRootChangeFailure.TARGET_UNAVAILABLE, "b"),
        )
        assertThat(fixture.store.currentRootState().pendingVolumeId).isNull()
        assertThat(fixture.store.currentRootState().activeVolumeId).isEqualTo("a")
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("bytes")
    }

    @Test
    fun `an interrupted cleanup removes only the old media tree`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "old-copy")
        fixture.write(fixture.rootB, "bucket/track.flac", "old-copy")
        fixture.store.commitRootMove(
            selection = OfflineRootSelection.Volume("b"),
            activeVolumeId = "b",
            generation = 1,
            cleanupVolumeId = "a",
        )

        val result = fixture.storage.recoverInterruptedRootChange(fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Completed(
                activeVolumeId = "b",
                movedFiles = 0,
                generation = 1,
                cleanupPending = false,
            ),
        )
        assertThat(fixture.storage.mediaDir(fixture.rootA).exists()).isFalse()
        assertThat(fixture.store.currentRootState().cleanupVolumeId).isNull()
    }

    @Test
    fun `interrupted cleanup keeps the old root when the published target is unavailable`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "only-readable-copy")
        fixture.store.commitRootMove(
            selection = OfflineRootSelection.Volume("b"),
            activeVolumeId = "b",
            generation = 1,
            cleanupVolumeId = "a",
        )
        fixture.rootList = listOf(fixture.rootA, fixture.internal)

        val result = fixture.storage.recoverInterruptedRootChange(fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Completed(
                activeVolumeId = "b",
                movedFiles = 0,
                generation = 1,
                cleanupPending = true,
            ),
        )
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("only-readable-copy")
        assertThat(fixture.store.currentRootState().cleanupVolumeId).isEqualTo("a")
    }

    @Test
    fun `a symlinked source entry aborts the move without deleting anything`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.pinA()
        fixture.write(fixture.rootA, "bucket/track.flac", "bytes")
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val link = File(fixture.storage.mediaDir(fixture.rootA), "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess)

        val result = fixture.storage.changeRoot(OfflineRootSelection.Volume("b"), fixture.gate)

        assertThat(result).isEqualTo(
            OfflineRootChangeResult.Failed(
                OfflineRootChangeFailure.INVALID_TREE,
                OfflinePathRules.MEDIA_DIRECTORY_NAME,
            ),
        )
        assertThat(fixture.read(fixture.rootA, "bucket/track.flac")).isEqualTo("bytes")
        assertThat(fixture.gate.events).containsExactly("pause", "resume").inOrder()
        assertThat(fixture.store.currentRootState().activeVolumeId).isEqualTo("a")
        assertThat(fixture.store.currentRootState().pendingVolumeId).isNull()
    }
}
