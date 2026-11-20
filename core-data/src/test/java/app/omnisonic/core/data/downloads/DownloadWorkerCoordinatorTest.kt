package app.omnisonic.core.data.downloads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadWorkerCoordinatorTest : DatabaseTestBase() {

    private var now = 1_000L

    private class WriteFailDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        var failWrites = false

        override val data = state.asStateFlow()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("injected write failure")
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private fun repository() = DownloadQueueRepository(downloadDao)

    private fun coordinator(
        workerCount: Int = 4,
        settings: DownloadQueueSettingsStore = DownloadQueueSettingsStore.inMemory(),
        runGate: ProviderRunGate = ProviderRunGate { ProviderStatus.OK },
        repository: DownloadQueueRepository = repository(),
        workStart: DownloadWorkStart = DownloadWorkStart.None,
    ) = DownloadWorkerCoordinator(
        repository = repository,
        settings = settings,
        runGate = runGate,
        workerCount = workerCount,
        nowMs = { now },
        workStart = workStart,
    )

    private suspend fun seed(
        providerId: Long,
        mediaKey: String,
        status: DownloadStatus = DownloadStatus.PENDING,
        errorCount: Int = 0,
        progressPercent: Int = 0,
        lastError: String? = null,
        createdAt: Long = 1_000,
        updatedAt: Long = 1_000,
    ): DownloadItemEntity = seedDownloadItem(
        providerId = providerId,
        status = status,
        mediaKey = mediaKey,
        errorCount = errorCount,
        progressPercent = progressPercent,
        lastError = lastError,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private suspend fun row(id: Long): DownloadItemEntity? = downloadDao.selectById(id)

    @Test
    fun `a claim requires a due pending row and fences every later write`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()

        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        assertThat(lease.rowId).isEqualTo(seeded.id)
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)
        assertThat(coordinator.reportProgress(lease, 40)).isTrue()
        assertThat(row(seeded.id)!!.progressPercent).isEqualTo(40)
        assertThat(coordinator.complete(lease)).isTrue()
        assertThat(row(seeded.id)).isNull()
        assertThat(coordinator.complete(lease)).isFalse()
        assertThat(coordinator.reportProgress(lease, 50)).isFalse()
        assertThat(coordinator.recordFailure(lease, "late")).isNull()
        coordinator.release(lease)
        assertThat(row(seeded.id)).isNull()
    }

    @Test
    fun `the worker cap bounds claims and invalid counts sanitize to one`() = runDbTest {
        repeat(3) { seed(providerId = 1, mediaKey = "k$it", createdAt = 1_000L + it) }
        val coordinator = coordinator(workerCount = 2)

        val first = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val second = coordinator.claimNext(ProfileScope.Unrestricted)!!

        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        assertThat(coordinator.activeLeaseCount()).isEqualTo(2)

        coordinator.release(first)
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNotNull()
        assertThat(coordinator.activeLeaseCount()).isEqualTo(2)
        coordinator.release(second)

        assertThat(coordinator(workerCount = 0).workerCount).isEqualTo(1)
        assertThat(coordinator(workerCount = -3).workerCount).isEqualTo(1)
    }

    @Test
    fun `an empty queue still records and clears the global gate`() = runDbTest {
        val settings = DownloadQueueSettingsStore.inMemory()
        val coordinator = coordinator(settings = settings)

        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        assertThat(coordinator.pauseAll()).isEqualTo(0)
        assertThat(settings.isGloballyPaused()).isTrue()
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        assertThat(coordinator.resumeAll()).isEqualTo(0)
        assertThat(settings.isGloballyPaused()).isFalse()
    }

    @Test
    fun `independent coordinators never claim the same row twice`() = runDbTest {
        repeat(5) { seed(providerId = 1, mediaKey = "k$it", createdAt = 1_000L + it) }
        val first = coordinator(workerCount = 5)
        val second = coordinator(workerCount = 5)

        val leases = withContext(Dispatchers.Default) {
            (0..1).map { index ->
                async {
                    val coordinator = if (index == 0) first else second
                    val collected = mutableListOf<DownloadLease>()
                    while (true) {
                        collected += coordinator.claimNext(ProfileScope.Unrestricted) ?: break
                    }
                    collected
                }
            }.awaitAll().flatten()
        }

        assertThat(leases.map { it.rowId }).containsNoDuplicates()
        assertThat(leases).hasSize(5)
        assertThat(downloadDao.selectByStatus(DownloadStatus.ACTIVE.name, ProfileScope.Unrestricted)).hasSize(5)
    }

    @Test
    fun `concurrent claims on one coordinator stay inside the cap`() = runDbTest {
        repeat(8) { seed(providerId = 1, mediaKey = "k$it", createdAt = 1_000L + it) }
        val coordinator = coordinator(workerCount = 3)

        val leases = withContext(Dispatchers.Default) {
            (1..8).map { async { coordinator.claimNext(ProfileScope.Unrestricted) } }.awaitAll()
        }.filterNotNull()

        assertThat(leases.map { it.rowId }).containsNoDuplicates()
        assertThat(leases).hasSize(3)
        assertThat(coordinator.activeLeaseCount()).isEqualTo(3)
        assertThat(downloadDao.selectByStatus(DownloadStatus.PENDING.name, ProfileScope.Unrestricted)).hasSize(5)
    }

    @Test
    fun `a blocked provider is skipped without mutation or head of line blocking`() = runDbTest {
        val blocked = seed(providerId = 1, mediaKey = "blocked", createdAt = 1_000)
        val open = seed(providerId = 2, mediaKey = "open", createdAt = 2_000)
        val coordinator = coordinator(
            runGate = { providerId -> if (providerId == 1L) ProviderStatus.NEEDS_WIFI else ProviderStatus.OK },
        )

        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        assertThat(lease.rowId).isEqualTo(open.id)
        val untouched = row(blocked.id)!!
        assertThat(untouched.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(untouched.updatedAt).isEqualTo(1_000L)
        assertThat(untouched.errorCount).isEqualTo(0)
        assertThat(untouched.lastError).isNull()
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
    }

    @Test
    fun `global pause parks claimable rows persists the gate and blocks new work`() = runDbTest {
        val pending = seed(providerId = 1, mediaKey = "pending", createdAt = 1_000)
        val paused = seed(providerId = 1, mediaKey = "paused", status = DownloadStatus.PAUSED, createdAt = 2_000)
        val userPaused = seed(providerId = 1, mediaKey = "user", status = DownloadStatus.USER_PAUSED, createdAt = 3_000)
        val failed = seed(providerId = 1, mediaKey = "failed", status = DownloadStatus.FAILED, createdAt = 4_000)
        val settings = DownloadQueueSettingsStore.inMemory()
        val coordinator = coordinator(settings = settings)
        coordinator.claimNext(ProfileScope.Unrestricted)

        assertThat(coordinator.pauseAll()).isEqualTo(1)
        assertThat(row(pending.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(row(paused.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(row(userPaused.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)
        assertThat(row(failed.id)!!.status).isEqualTo(DownloadStatus.FAILED.name)
        assertThat(settings.isGloballyPaused()).isTrue()

        val unique = DownloadItemEntity(
            mediaKey = "new",
            providerId = 1,
            status = DownloadStatus.PENDING.name,
            createdAt = 9_000,
            updatedAt = 9_000,
        )
        assertThat(coordinator.enqueue(unique)).isTrue()
        assertThat(downloadDao.selectByMediaKey("new")!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()

        assertThat(coordinator.resumeAll()).isEqualTo(3)
        assertThat(row(paused.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(row(userPaused.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)
        assertThat(row(failed.id)!!.status).isEqualTo(DownloadStatus.FAILED.name)
        assertThat(settings.isGloballyPaused()).isFalse()
    }

    @Test
    fun `pause persists its gate before touching rows`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val store = WriteFailDataStore()
        val settings = DownloadQueueSettingsStore(store)
        val coordinator = coordinator(settings = settings)
        store.failWrites = true

        assertThat(runCatching { coordinator.pauseAll() }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `resume releases rows before clearing its gate`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1", status = DownloadStatus.PAUSED)
        val store = WriteFailDataStore()
        val settings = DownloadQueueSettingsStore(store)
        settings.setGloballyPaused(true)
        val coordinator = coordinator(settings = settings)
        store.failWrites = true

        assertThat(runCatching { coordinator.resumeAll() }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(settings.isGloballyPaused()).isTrue()
    }

    @Test
    fun `pausing an already globally paused row makes it sticky`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()

        coordinator.pauseAll()
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(coordinator.pauseOne(seeded.id)).isTrue()
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)
        assertThat(coordinator.pauseOne(seeded.id)).isFalse()

        coordinator.resumeAll()
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)

        assertThat(coordinator.resumeOne(seeded.id)).isTrue()
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `resume one enters the current global gate`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1", status = DownloadStatus.USER_PAUSED)
        val settings = DownloadQueueSettingsStore.inMemory()
        val coordinator = coordinator(settings = settings)

        coordinator.pauseAll()
        assertThat(coordinator.resumeOne(seeded.id)).isTrue()
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)

        coordinator.resumeAll()
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(coordinator.resumeOne(seeded.id)).isFalse()

        val second = seed(providerId = 1, mediaKey = "k2", status = DownloadStatus.USER_PAUSED)
        assertThat(coordinator.resumeOne(second.id)).isTrue()
        assertThat(row(second.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `pause one leaves failed rows parked`() = runDbTest {
        val failed = seed(providerId = 1, mediaKey = "k1", status = DownloadStatus.FAILED)

        assertThat(coordinator().pauseOne(failed.id)).isFalse()
        assertThat(row(failed.id)!!.status).isEqualTo(DownloadStatus.FAILED.name)
    }

    @Test
    fun `cancel removes the row from every known state`() = runDbTest {
        val coordinator = coordinator()

        for (status in DownloadStatus.entries) {
            val seeded = seed(providerId = 1, mediaKey = "k-${status.name}", status = status, createdAt = 1_000)
            assertThat(coordinator.cancel(seeded.id)).isTrue()
            assertThat(row(seeded.id)).isNull()
            assertThat(coordinator.cancel(seeded.id)).isFalse()
        }
    }

    @Test
    fun `cancel fences an active worker and never recreates the row`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        assertThat(coordinator.cancel(seeded.id)).isTrue()
        assertThat(row(seeded.id)).isNull()
        assertThat(coordinator.reportProgress(lease, 10)).isFalse()
        assertThat(coordinator.recordFailure(lease, "late")).isNull()
        assertThat(coordinator.complete(lease)).isFalse()
        coordinator.release(lease)
        assertThat(row(seeded.id)).isNull()
    }

    @Test
    fun `an unrecognized persisted status is never claimed cancelled or transitioned`() = runDbTest {
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = "legacy",
                providerId = 1,
                status = "RUNNING",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        val legacy = downloadDao.selectByMediaKey("legacy")!!
        val coordinator = coordinator()

        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        assertThat(coordinator.cancel(legacy.id)).isFalse()
        assertThat(coordinator.pauseOne(legacy.id)).isFalse()
        assertThat(coordinator.retry(legacy.id)).isFalse()
        coordinator.pauseAll()
        coordinator.resumeAll()

        assertThat(row(legacy.id)!!.status).isEqualTo("RUNNING")
    }

    @Test
    fun `stale settlements lose to pause and resume lease ABA`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val first = coordinator.claimNext(ProfileScope.Unrestricted)!!

        coordinator.pauseOne(seeded.id)
        coordinator.resumeOne(seeded.id)
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()

        coordinator.release(first)
        val second = coordinator.claimNext(ProfileScope.Unrestricted)!!
        assertThat(second.token).isNotEqualTo(first.token)

        assertThat(coordinator.reportProgress(first, 50)).isFalse()
        assertThat(coordinator.recordFailure(first, "stale")).isNull()
        assertThat(coordinator.complete(first)).isFalse()
        assertThat(coordinator.reportProgress(second, 25)).isTrue()
        assertThat(row(seeded.id)!!.progressPercent).isEqualTo(25)
        assertThat(coordinator.complete(second)).isTrue()
    }

    @Test
    fun `a paused but not exited lease keeps consuming the worker slot`() = runDbTest {
        val first = seed(providerId = 1, mediaKey = "a", createdAt = 1_000)
        seed(providerId = 1, mediaKey = "b", createdAt = 2_000)
        val coordinator = coordinator(workerCount = 1)
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        assertThat(lease.rowId).isEqualTo(first.id)

        coordinator.pauseOne(first.id)
        coordinator.resumeOne(first.id)

        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        coordinator.release(lease)
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNotNull()
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
    }

    @Test
    fun `counted failures return to pending with the retry delay and preserve progress`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1", createdAt = 0, updatedAt = 0)
        val coordinator = coordinator()
        var at = 0L

        for (attempt in 1..5) {
            if (attempt > 1) {
                now = at - 1
                assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
            }
            now = at
            val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
            assertThat(coordinator.reportProgress(lease, 40)).isTrue()
            assertThat(coordinator.recordFailure(lease, "network\nlost")).isEqualTo(DownloadStatus.PENDING)
            coordinator.release(lease)

            val stored = row(seeded.id)!!
            assertThat(stored.status).isEqualTo(DownloadStatus.PENDING.name)
            assertThat(stored.errorCount).isEqualTo(attempt)
            assertThat(stored.updatedAt).isEqualTo(at)
            assertThat(stored.progressPercent).isEqualTo(40)
            assertThat(stored.lastError).isEqualTo("network lost")
            at += attempt * DownloadLifecycleRules.BACKOFF_STEP_MS
        }
    }

    @Test
    fun `the sixth failure parks the row and explicit retry revives it`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1", progressPercent = 30, createdAt = 0, updatedAt = 0)
        val coordinator = coordinator()
        var at = 0L

        for (attempt in 1..5) {
            now = at
            val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
            coordinator.recordFailure(lease, "attempt $attempt")
            coordinator.release(lease)
            at += attempt * DownloadLifecycleRules.BACKOFF_STEP_MS
        }
        now = at
        val last = coordinator.claimNext(ProfileScope.Unrestricted)!!
        assertThat(coordinator.recordFailure(last, "media\nrejected")).isEqualTo(DownloadStatus.FAILED)
        coordinator.release(last)

        val parked = row(seeded.id)!!
        assertThat(parked.status).isEqualTo(DownloadStatus.FAILED.name)
        assertThat(parked.errorCount).isEqualTo(6)
        assertThat(parked.progressPercent).isEqualTo(30)
        assertThat(parked.lastError).isEqualTo("media rejected")
        assertThat(parked.updatedAt).isEqualTo(at)
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()

        now += 1_000_000
        assertThat(coordinator.retry(seeded.id)).isTrue()
        val revived = row(seeded.id)!!
        assertThat(revived.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(revived.errorCount).isEqualTo(0)
        assertThat(revived.lastError).isNull()
        assertThat(revived.updatedAt).isEqualTo(now)
        assertThat(revived.progressPercent).isEqualTo(30)
    }

    @Test
    fun `retry targets paused work while the global gate is set`() = runDbTest {
        val failed = seed(providerId = 1, mediaKey = "k1", status = DownloadStatus.FAILED)
        val coordinator = coordinator()

        coordinator.pauseAll()
        assertThat(coordinator.retry(failed.id)).isTrue()
        assertThat(row(failed.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)

        coordinator.resumeAll()
        assertThat(row(failed.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `retry all affects only failed rows`() = runDbTest {
        val failed = seed(providerId = 1, mediaKey = "a", status = DownloadStatus.FAILED, createdAt = 1_000)
        val userPaused = seed(providerId = 1, mediaKey = "b", status = DownloadStatus.USER_PAUSED, createdAt = 2_000)
        val paused = seed(providerId = 1, mediaKey = "c", status = DownloadStatus.PAUSED, createdAt = 3_000)

        assertThat(coordinator().retryAll()).isEqualTo(1)

        assertThat(row(failed.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(row(userPaused.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)
        assertThat(row(paused.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
    }

    @Test
    fun `release after a claim returns to pending without counting a failure`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        coordinator.release(lease)

        val released = row(seeded.id)!!
        assertThat(released.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(released.errorCount).isEqualTo(0)
        assertThat(released.lastError).isNull()
        assertThat(coordinator.activeLeaseCount()).isEqualTo(0)
    }

    @Test
    fun `release does not overwrite a concurrent pause`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        coordinator.pauseOne(seeded.id)
        coordinator.release(lease)

        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)
    }

    @Test
    fun `release does not overwrite a committed failure`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        coordinator.recordFailure(lease, "first attempt")
        coordinator.release(lease)

        val stored = row(seeded.id)!!
        assertThat(stored.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(stored.errorCount).isEqualTo(1)
    }

    @Test
    fun `a cancelled worker releases its lease without counting a failure`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val started = CompletableDeferred<DownloadLease>()

        coroutineScope {
            val job = launch(Dispatchers.Default) {
                val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
                try {
                    started.complete(lease)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { coordinator.release(lease) }
                }
            }
            started.await()
            assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)

            job.cancelAndJoin()
        }

        val released = row(seeded.id)!!
        assertThat(released.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(released.errorCount).isEqualTo(0)
        assertThat(coordinator.activeLeaseCount()).isEqualTo(0)
    }

    @Test
    fun `startup recovery returns orphaned active rows to the gate state`() = runDbTest {
        val first = seed(providerId = 1, mediaKey = "a", status = DownloadStatus.ACTIVE, createdAt = 1_000)
        val second = seed(providerId = 1, mediaKey = "b", status = DownloadStatus.ACTIVE, createdAt = 2_000)
        val coordinator = coordinator()

        assertThat(coordinator.recoverOnStartup()).isEqualTo(2)
        assertThat(row(first.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(row(second.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)

        val third = seed(providerId = 1, mediaKey = "c", status = DownloadStatus.ACTIVE, createdAt = 3_000)
        val settings = DownloadQueueSettingsStore.inMemory()
        settings.setGloballyPaused(true)

        assertThat(coordinator(settings = settings).recoverOnStartup()).isEqualTo(1)
        assertThat(row(third.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
    }

    @Test
    fun `startup recovery closes the persisted gate crash gap before claims`() = runDbTest {
        val pending = seed(providerId = 1, mediaKey = "pending", createdAt = 1_000)
        val active = seed(providerId = 1, mediaKey = "active", status = DownloadStatus.ACTIVE, createdAt = 2_000)
        val settings = DownloadQueueSettingsStore.inMemory()
        settings.setGloballyPaused(true)
        val coordinator = coordinator(settings = settings)

        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        assertThat(coordinator.recoverOnStartup()).isEqualTo(1)

        assertThat(row(pending.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(row(active.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(row(pending.id)!!.updatedAt).isEqualTo(1_000L)
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
    }

    @Test
    fun `a reopened database recovers orphaned active rows under both gate states`(): Unit = runBlocking {
        val factory = OmnisonicDatabaseFactory(appContext)
        val name = "download-recovery-${System.nanoTime()}.db"
        val gateFile = File(appContext.cacheDir, "download-gate-${System.nanoTime()}.preferences_pb")
        val gate = DownloadQueueSettingsStore.createAt(gateFile)

        try {
            factory.open(name).let { database ->
                database.downloadDao().upsert(activeRow("gate-on"))
                database.close()
            }
            gate.setGloballyPaused(true)
            factory.open(name).let { database ->
                val coordinator = DownloadWorkerCoordinator(
                    DownloadQueueRepository(database.downloadDao()),
                    gate,
                    ProviderRunGate { ProviderStatus.OK },
                    workerCount = 2,
                )
                assertThat(coordinator.recoverOnStartup()).isEqualTo(1)
                assertThat(database.downloadDao().selectByMediaKey("gate-on")!!.status)
                    .isEqualTo(DownloadStatus.PAUSED.name)
                database.close()
            }

            factory.open(name).let { database ->
                database.downloadDao().upsert(activeRow("gate-off"))
                database.close()
            }
            gate.setGloballyPaused(false)
            factory.open(name).let { database ->
                val coordinator = DownloadWorkerCoordinator(
                    DownloadQueueRepository(database.downloadDao()),
                    gate,
                    ProviderRunGate { ProviderStatus.OK },
                    workerCount = 2,
                )
                assertThat(coordinator.recoverOnStartup()).isEqualTo(1)
                assertThat(database.downloadDao().selectByMediaKey("gate-off")!!.status)
                    .isEqualTo(DownloadStatus.PENDING.name)
                assertThat(database.downloadDao().selectByMediaKey("gate-on")!!.status)
                    .isEqualTo(DownloadStatus.PAUSED.name)
                database.close()
            }
        } finally {
            appContext.deleteDatabase(name)
            gateFile.delete()
        }
    }

    @Test
    fun `provider cascade removes an active row and the stale lease cannot recreate it`() = runDbTest {
        newProvider(id = 1)
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        providerDao.deleteById(1)

        assertThat(row(seeded.id)).isNull()
        assertThat(coordinator.reportProgress(lease, 50)).isFalse()
        assertThat(coordinator.recordFailure(lease, "late")).isNull()
        assertThat(coordinator.complete(lease)).isFalse()
        coordinator.release(lease)
        assertThat(row(seeded.id)).isNull()
    }

    @Test
    fun `scoped reads hide excluded providers while workers read unrestricted`() = runDbTest {
        val hidden = seed(providerId = 1, mediaKey = "hidden", createdAt = 1_000)
        seed(providerId = 2, mediaKey = "visible", createdAt = 2_000)
        val repository = repository()
        val scope = ProfileScope().excluding(listOf(1L))

        assertThat(repository.selectVisible(scope).map { it.mediaKey }).containsExactly("visible")
        assertThat(repository.selectDue(scope, now).map { it.mediaKey }).containsExactly("visible")
        assertThat(repository.selectDue(ProfileScope.Unrestricted, now).map { it.mediaKey })
            .containsExactly("hidden", "visible")

        val coordinator = coordinator()
        val visibleLease = coordinator.claimNext(scope)!!
        assertThat(visibleLease.providerId).isEqualTo(2)

        val hiddenLease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        assertThat(hiddenLease.rowId).isEqualTo(hidden.id)
    }

    @Test
    fun `due ordering uses effective due time and deterministic ties`() = runDbTest {
        seed(providerId = 1, mediaKey = "not-yet-due", errorCount = 1, createdAt = 5_000, updatedAt = 200_000)
        seed(providerId = 1, mediaKey = "late", errorCount = 3, createdAt = 0, updatedAt = 0)
        seed(providerId = 1, mediaKey = "early", errorCount = 1, createdAt = 9_000, updatedAt = 0)
        seed(providerId = 1, mediaKey = "tieA", errorCount = 1, createdAt = 3_000, updatedAt = 90_000)
        seed(providerId = 1, mediaKey = "tieB", errorCount = 1, createdAt = 3_000, updatedAt = 90_000)

        val ordered = repository().selectDue(ProfileScope.Unrestricted, nowMs = 100_000).map { it.mediaKey }

        assertThat(ordered).containsExactly("early", "late", "tieA", "tieB").inOrder()
    }

    private fun activeRow(mediaKey: String) = DownloadItemEntity(
        mediaKey = mediaKey,
        providerId = 1,
        status = DownloadStatus.ACTIVE.name,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    @Test
    fun `pause signals only the affected lease while cancel signals its own row`() = runDbTest {
        val first = seed(providerId = 1, mediaKey = "a", createdAt = 1_000)
        val second = seed(providerId = 2, mediaKey = "b", createdAt = 2_000)
        val coordinator = coordinator()
        val firstLease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val secondLease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val firstSignal = coordinator.interruptionFor(firstLease)
        val secondSignal = coordinator.interruptionFor(secondLease)

        assertThat(coordinator.pauseOne(first.id)).isTrue()

        assertThat(firstSignal.isCompleted).isTrue()
        assertThat(firstSignal.getCompleted()).isEqualTo(DownloadInterruption.PAUSE)
        assertThat(secondSignal.isCompleted).isFalse()

        assertThat(coordinator.cancel(second.id)).isTrue()
        assertThat(secondSignal.isCompleted).isTrue()
        assertThat(secondSignal.getCompleted()).isEqualTo(DownloadInterruption.CANCEL)
    }

    @Test
    fun `pause all signals every active lease and release completes its signal`() = runDbTest {
        seed(providerId = 1, mediaKey = "a", createdAt = 1_000)
        seed(providerId = 1, mediaKey = "b", createdAt = 2_000)
        val coordinator = coordinator()
        val first = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val second = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val firstSignal = coordinator.interruptionFor(first)
        val secondSignal = coordinator.interruptionFor(second)

        assertThat(coordinator.pauseAll()).isEqualTo(2)

        assertThat(firstSignal.getCompleted()).isEqualTo(DownloadInterruption.PAUSE)
        assertThat(secondSignal.getCompleted()).isEqualTo(DownloadInterruption.PAUSE)

        coordinator.release(first)
        assertThat(coordinator.interruptionFor(first).getCompleted()).isNull()
    }

    @Test
    fun `release completes the original pending interruption signal`() = runDbTest {
        seed(providerId = 1, mediaKey = "a")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val signal = coordinator.interruptionFor(lease)
        assertThat(signal.isCompleted).isFalse()

        coordinator.release(lease)

        assertThat(signal.isCompleted).isTrue()
        assertThat(signal.getCompleted()).isNull()
        assertThat(coordinator.activeLeaseCount()).isEqualTo(0)
    }

    @Test
    fun `park fails closed without an error increment and cannot be re-claimed`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        assertThat(coordinator.park(lease, "download parked: unsupported")).isTrue()

        val parked = row(seeded.id)!!
        assertThat(parked.status).isEqualTo(DownloadStatus.FAILED.name)
        assertThat(parked.errorCount).isEqualTo(0)
        assertThat(parked.updatedAt).isEqualTo(1_000L)
        assertThat(parked.lastError).isEqualTo("download parked: unsupported")
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        assertThat(coordinator.park(lease, "again")).isFalse()
    }

    @Test
    fun `progress accepts the indeterminate marker and bounds percentages`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        assertThat(coordinator.reportProgress(lease, -1)).isTrue()
        assertThat(row(seeded.id)!!.progressPercent).isEqualTo(-1)

        assertThat(coordinator.reportProgress(lease, 250)).isTrue()
        assertThat(row(seeded.id)!!.progressPercent).isEqualTo(100)
    }

    @Test
    fun `worker count changes admit new slots without preempting active leases`() = runDbTest {
        repeat(4) { seed(providerId = 1, mediaKey = "k$it", createdAt = 1_000L + it) }
        val coordinator = coordinator(workerCount = 1)

        val first = coordinator.claimNext(ProfileScope.Unrestricted)!!
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()

        coordinator.updateWorkerCount(3)
        val second = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val third = coordinator.claimNext(ProfileScope.Unrestricted)!!
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        assertThat(coordinator.activeLeaseCount()).isEqualTo(3)

        coordinator.updateWorkerCount(1)
        assertThat(coordinator.workerCount).isEqualTo(1)
        assertThat(coordinator.activeLeaseCount()).isEqualTo(3)

        coordinator.complete(first)
        coordinator.complete(second)
        coordinator.complete(third)
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNotNull()
        assertThat(coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
    }

    @Test
    fun `active progress and lease flows expose claimed work for the notification`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1", progressPercent = 7)
        val coordinator = coordinator()

        assertThat(coordinator.activeLeases.value).isEqualTo(0)
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!

        assertThat(coordinator.activeLeases.value).isEqualTo(1)
        assertThat(coordinator.activeProgress.value).containsExactly(
            DownloadActiveProgress(rowId = seeded.id, title = "Track one", progressPercent = 7),
        )

        coordinator.reportProgress(lease, 42)
        assertThat(coordinator.activeProgress.value.single().progressPercent).isEqualTo(42)

        coordinator.reportProgress(lease, -1)
        assertThat(coordinator.activeProgress.value.single().progressPercent).isEqualTo(-1)

        coordinator.complete(lease)
        assertThat(coordinator.activeLeases.value).isEqualTo(0)
        assertThat(coordinator.activeProgress.value).isEmpty()
    }

    @Test
    fun `committed mutations request the foreground runtime without duplicate signals`() = runDbTest {
        val starts = mutableListOf<Unit>()
        val coordinator = coordinator(workStart = DownloadWorkStart { starts += Unit })
        val pending = DownloadItemEntity(
            id = 0,
            mediaKey = "enqueue-1",
            providerId = 1,
            status = DownloadStatus.PENDING.name,
            title = "Track",
            createdAt = 1_000,
            updatedAt = 1_000,
        )

        assertThat(coordinator.enqueue(pending)).isTrue()
        assertThat(starts).hasSize(1)
        assertThat(coordinator.enqueue(pending)).isFalse()
        assertThat(starts).hasSize(1)

        val failed = seed(providerId = 1, mediaKey = "failed-1", status = DownloadStatus.FAILED)
        assertThat(coordinator.retry(failed.id)).isTrue()
        assertThat(starts).hasSize(2)

        val paused = seed(providerId = 1, mediaKey = "paused-1", status = DownloadStatus.USER_PAUSED)
        assertThat(coordinator.resumeOne(paused.id)).isTrue()
        assertThat(starts).hasSize(3)

        coordinator.pauseAll()
        assertThat(starts).hasSize(3)
        assertThat(coordinator.resumeAll()).isEqualTo(3)
        assertThat(starts).hasSize(4)
    }

    @Test
    fun `startup recovery never resets a row owned by a live lease`() = runDbTest {
        val seeded = seed(providerId = 1, mediaKey = "k1")
        val coordinator = coordinator()

        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        assertThat(coordinator.recoverOnStartup()).isEqualTo(0)
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)
        assertThat(coordinator.reportProgress(lease, 25)).isTrue()
    }
}
