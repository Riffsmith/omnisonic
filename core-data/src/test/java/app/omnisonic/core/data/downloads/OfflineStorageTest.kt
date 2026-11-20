package app.omnisonic.core.data.downloads

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OfflineStorageTest {

    @TempDir
    lateinit var tempDir: File

    private val floor = OfflinePathRules.MIN_FREE_SPACE_BYTES

    private fun root(id: String): OfflineRoot =
        OfflineRoot(id, File(tempDir, "root-$id").apply { mkdirs() })

    private fun storage(
        roots: List<OfflineRoot>,
        store: DownloadQueueSettingsStore = DownloadQueueSettingsStore.inMemory(),
        freeBytes: Long = floor * 10,
        writable: (File) -> Boolean = { true },
    ) = OfflineStorage(
        roots = OfflineRootSource { roots },
        settings = store,
        usableSpace = { freeBytes },
        writableProbe = writable,
    )

    @Test
    fun `auto prefers the external root and creates the media directory and marker`(): Unit = runBlocking {
        val external = root("external")
        val internal = root(OfflineRoot.INTERNAL_VOLUME)
        val store = DownloadQueueSettingsStore.inMemory()
        val storage = storage(listOf(external, internal), store)

        val result = storage.prepare()

        assertThat(result).isInstanceOf(OfflineStorageState.Ready::class.java)
        val ready = result as OfflineStorageState.Ready
        assertThat(ready.root.volumeId).isEqualTo("external")
        assertThat(ready.mediaDir.isDirectory).isTrue()
        assertThat(File(ready.mediaDir, ".nomedia").isFile).isTrue()
        assertThat(File(ready.mediaDir, ".nomedia").length()).isEqualTo(0L)
        assertThat(store.currentRootState().activeVolumeId).isEqualTo("external")
    }

    @Test
    fun `auto falls back to internal when the external root is not a directory`(): Unit = runBlocking {
        val blocked = File(tempDir, "blocked-external").apply { writeText("not a directory") }
        val external = OfflineRoot("external", blocked)
        val internal = root(OfflineRoot.INTERNAL_VOLUME)
        val store = DownloadQueueSettingsStore.inMemory()
        val storage = storage(listOf(external, internal), store)

        val result = storage.prepare() as OfflineStorageState.Ready

        assertThat(result.root.volumeId).isEqualTo(OfflineRoot.INTERNAL_VOLUME)
        assertThat(store.currentRootState().activeVolumeId).isEqualTo(OfflineRoot.INTERNAL_VOLUME)
    }

    @Test
    fun `auto falls back to internal when the external root is unwritable`(): Unit = runBlocking {
        val external = root("external")
        val internal = root(OfflineRoot.INTERNAL_VOLUME)
        val blockedPrefix = external.directory.absolutePath
        val storage = storage(
            roots = listOf(external, internal),
            writable = { !it.absolutePath.startsWith(blockedPrefix) },
        )

        val result = storage.prepare() as OfflineStorageState.Ready

        assertThat(result.root.volumeId).isEqualTo(OfflineRoot.INTERNAL_VOLUME)
    }

    @Test
    fun `an explicit internal selection is honoured`(): Unit = runBlocking {
        val external = root("external")
        val internal = root(OfflineRoot.INTERNAL_VOLUME)
        val store = DownloadQueueSettingsStore.inMemory()
        store.setRootSelection(OfflineRootSelection.Volume(OfflineRoot.INTERNAL_VOLUME))
        val storage = storage(listOf(external, internal), store)

        val result = storage.prepare() as OfflineStorageState.Ready

        assertThat(result.root.volumeId).isEqualTo(OfflineRoot.INTERNAL_VOLUME)
    }

    @Test
    fun `an unavailable explicit volume is reported and never substituted`(): Unit = runBlocking {
        val external = root("external")
        val internal = root(OfflineRoot.INTERNAL_VOLUME)
        val store = DownloadQueueSettingsStore.inMemory()
        store.setRootSelection(OfflineRootSelection.Volume("missing"))
        val storage = storage(listOf(external, internal), store)

        val result = storage.prepare()

        assertThat(result).isEqualTo(
            OfflineStorageState.Blocked(OfflineStorageBlock.ROOT_UNAVAILABLE),
        )
        assertThat(store.currentRootState().activeVolumeId).isNull()
        assertThat(storage.activeRoot()).isEqualTo(OfflineRootResolution.Unavailable(null))
    }

    @Test
    fun `a published root that disappears is unavailable rather than an empty library`(): Unit = runBlocking {
        val external = root("external")
        val internal = root(OfflineRoot.INTERNAL_VOLUME)
        val store = DownloadQueueSettingsStore.inMemory()
        store.publishRoot(OfflineRootSelection.Volume("external"), "external", 0)
        val storage = storage(listOf(internal), store)

        assertThat(storage.prepare()).isEqualTo(
            OfflineStorageState.Blocked(OfflineStorageBlock.ROOT_UNAVAILABLE),
        )
        assertThat(storage.activeRoot()).isEqualTo(OfflineRootResolution.Unavailable("external"))
        assertThat(storage.resolveStoredPath("OfflineMedias/a/x.flac")).isNull()
    }

    @Test
    fun `the free-space floor is inclusive and independent of the required bytes`(): Unit = runBlocking {
        val external = root("external")
        val atFloor = storage(listOf(external), freeBytes = floor)

        assertThat(atFloor.prepare()).isInstanceOf(OfflineStorageState.Ready::class.java)
        assertThat(atFloor.prepare(10)).isEqualTo(
            OfflineStorageState.Blocked(OfflineStorageBlock.LOW_SPACE, floor, 10),
        )

        val belowFloor = storage(listOf(external), freeBytes = floor - 1)
        assertThat(belowFloor.prepare()).isEqualTo(
            OfflineStorageState.Blocked(OfflineStorageBlock.LOW_SPACE, floor - 1, 0),
        )

        val exactlyRequired = storage(listOf(external), freeBytes = floor + 10)
        assertThat(exactlyRequired.prepare(10)).isInstanceOf(OfflineStorageState.Ready::class.java)
    }

    @Test
    fun `an unwritable root is blocked before any path is used`(): Unit = runBlocking {
        val external = root("external")
        val storage = storage(listOf(external), writable = { false })

        assertThat(storage.prepare()).isEqualTo(
            OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE),
        )
    }

    @Test
    fun `stored paths resolve canonically and reject escape`(): Unit = runBlocking {
        val external = root("external")
        val store = DownloadQueueSettingsStore.inMemory()
        val storage = storage(listOf(external), store)
        storage.prepare()
        val relative = "OfflineMedias/a/track.flac"
        val file = File(external.directory, relative).apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }

        assertThat(storage.resolveStoredPath(relative)).isEqualTo(file.canonicalFile)
        assertThat(storage.resolveStoredPath("../escape.flac")).isNull()
        assertThat(storage.resolveStoredPath("/etc/passwd")).isNull()
    }

    @Test
    fun `a symlinked child cannot escape the published root`(): Unit = runBlocking {
        val external = root("external")
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(outside, "secret.flac").writeText("secret")
        val storage = storage(listOf(external))
        val ready = storage.prepare() as OfflineStorageState.Ready
        val link = File(ready.mediaDir, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess)

        assertThat(storage.resolveStoredPath("OfflineMedias/link/secret.flac")).isNull()
    }

    @Test
    fun `path planning keeps providers and variants apart`(): Unit = runBlocking {
        val storage = storage(listOf(root("external")))
        val base = OfflineMediaIdentity(1, "/music/a.flac")

        val first = storage.planPath(base) as OfflinePathResult.Planned
        val otherProvider = storage.planPath(base.copy(providerId = 2)) as OfflinePathResult.Planned
        val otherVariant = storage.planPath(base.copy(format = "mp3")) as OfflinePathResult.Planned

        assertThat(first.plan.relativePath).isNotEqualTo(otherProvider.plan.relativePath)
        assertThat(first.plan.relativePath).isNotEqualTo(otherVariant.plan.relativePath)
    }
}
