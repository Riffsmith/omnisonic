package app.omnisonic.provider.api

import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class CollectingSink : LogSink {
    val entries = mutableListOf<String>()

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        entries += "$tag: $message"
    }
}

private class FakeOutboxStore : OutboxStore {
    val tasks = mutableListOf<OutboxTask>()
    val completed = mutableListOf<Long>()
    val failures = mutableListOf<TrackedFailure>()
    val progressed = mutableListOf<TrackedProgress>()

    data class TrackedFailure(val id: Long, val attempts: Int, val nextAttemptAt: Long)
    data class TrackedProgress(val id: Long, val payloadJson: String?)

    override suspend fun dueTasks(now: Long, providerId: Long?): List<OutboxTask> = tasks.toList()

    override suspend fun complete(id: Long) {
        completed += id
        tasks.removeAll { it.id == id }
    }

    override suspend fun fail(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String) {
        failures += TrackedFailure(id, attempts, nextAttemptAt)
        tasks.removeAll { it.id == id }
    }

    override suspend fun progress(id: Long, payloadJson: String?, nextAttemptAt: Long) {
        progressed += TrackedProgress(id, payloadJson)
        tasks.removeAll { it.id == id }
    }

    fun add(action: String, payload: OutboxActionPayload, attempts: Int = 0): OutboxTask {
        val encoded = if (defaultValued(payload)) null else moshi.adapter(OutboxActionPayload::class.java).toJson(payload)
        val task = OutboxTask(
            id = (tasks.size + 1L),
            providerId = 7L,
            mediaType = "SONG",
            externalId = "song-1",
            action = action,
            attempts = attempts,
            payloadJson = encoded,
        )
        tasks += task
        return task
    }

    private fun defaultValued(payload: OutboxActionPayload) =
        payload == OutboxActionPayload()
}

private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

private val payloadAdapter = moshi.adapter(OutboxActionPayload::class.java)

class OutboxDrainerTest {

    private val store = FakeOutboxStore()
    private val provider = RecordingProvider()
    private val sink = CollectingSink()

    private fun drainer(clock: () -> Long = { 1_000L }): OutboxDrainer = OutboxDrainer(
        store = store,
        providerLocator = { provider },
        clock = clock,
        logger = OmnisonicLogger(sink),
    )

    @Test
    fun `play count delta fully submitted completes the task`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 3))
        provider.submitPlaysResult = 3

        val report = drain.drain()

        assertEquals(1, report.completed)
        assertEquals(listOf(RecordingProvider.SubmittedPlay("song-1", 3, null)), provider.submittedPlays)
    }

    @Test
    fun `play count delta carries recorded last played timestamp`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 5_000L))
        provider.submitPlaysResult = 1

        drain.drain()

        assertEquals(5_000L, provider.submittedPlays.single().lastPlayedAtMs)
    }

    @Test
    fun `partially submitted delta records progress without re-attempting accepted part`() = runTest {
        val drain = drainer()
        val task = store.add("play_count", OutboxActionPayload(playCountDelta = 3, lastPlayedAt = 5_000L))
        provider.submitPlaysResult = 1

        val report = drain.drain()

        assertEquals(1, report.progressed)
        val remaining = store.progressed.single { it.id == task.id }.payloadJson
        assertEquals(OutboxActionPayload(playCountDelta = 2, lastPlayedAt = 5_000L), remaining?.let { payloadAdapter.fromJson(it) })
    }

    @Test
    fun `zero submissions defers with base backoff`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 2))

        val report = drain.drain()

        assertEquals(1, report.deferred)
        val failure = store.failures.single()
        assertEquals(1, failure.attempts)
        assertEquals(1_000L + OutboxDrainer.BACKOFF_BASE_MS, failure.nextAttemptAt)
    }

    @Test
    fun `backoff caps at one hour`() = runTest {
        val drain = drainer()
        store.add("favorite", OutboxActionPayload(isFavorite = true), attempts = 9)
        provider.favoriteResult = false

        drain.drain()

        val failure = store.failures.single()
        assertEquals(10, failure.attempts)
        assertEquals(1_000L + OutboxDrainer.BACKOFF_MAX_MS, failure.nextAttemptAt)
    }

    @Test
    fun `favorite action reaches provider and completes on success`() = runTest {
        val drain = drainer()
        store.add("favorite", OutboxActionPayload(isFavorite = true))

        val report = drain.drain()

        assertEquals(1, report.completed)
        assertEquals(Triple(ProviderMediaType.SONG, "song-1", true), provider.favorites.single())
    }

    @Test
    fun `user rating action completes`() = runTest {
        val drain = drainer()
        store.add("user_rating", OutboxActionPayload(userRating = 4))

        val report = drain.drain()

        assertEquals(1, report.completed)
        assertEquals(4, provider.userRatings.single().third)
    }

    @Test
    fun `resume point above zero saves bookmark`() = runTest {
        val drain = drainer()
        store.add("resume_point", OutboxActionPayload(resumePointMs = 42_000L))

        drain.drain()

        assertEquals(listOf("song-1" to 42_000L), provider.savedBookmarks)
    }

    @Test
    fun `resume point zero clears the bookmark`() = runTest {
        val drain = drainer()
        store.add("resume_point", OutboxActionPayload(resumePointMs = 0L))

        drain.drain()

        assertEquals(listOf("song-1"), provider.deletedBookmarks)
        assertTrue(provider.savedBookmarks.isEmpty())
    }

    @Test
    fun `provider rejection defers favorite task`() = runTest {
        val drain = drainer()
        store.add("favorite", OutboxActionPayload(isFavorite = false))
        provider.favoriteResult = false

        val report = drain.drain()

        assertEquals(1, report.deferred)
        assertEquals(1, store.failures.single().attempts)
    }

    @Test
    fun `provider exception defers task`() = runTest {
        val drain = drainer()
        store.add("favorite", OutboxActionPayload(isFavorite = true))
        provider.favoriteResult = false
        provider.failNext = true

        val report = drain.drain()

        assertEquals(1, report.deferred)
        assertEquals(1, store.failures.single().attempts)
    }

    @Test
    fun `unresolvable provider defers task for a later registry lookup`() = runTest {
        val task = OutboxTask(
            id = 11L,
            providerId = 99L,
            mediaType = "SONG",
            externalId = "song-9",
            action = "favorite",
            attempts = 0,
            payloadJson = payloadAdapter.toJson(OutboxActionPayload(isFavorite = true)),
        )
        store.tasks += task
        val drain = OutboxDrainer(
            store = store,
            providerLocator = { null },
            clock = { 0L },
            logger = OmnisonicLogger(sink),
        )

        val report = drain.drain()

        assertEquals(1, report.deferred)
        assertTrue(store.failures.isEmpty())
        assertTrue(sink.entries.single().contains("no registered provider"))
    }

    @Test
    fun `unknown action is dropped with a warning`() = runTest {
        val drain = drainer()
        store.add("legacy_scrobble_burst", OutboxActionPayload(playCountDelta = 5))

        val report = drain.drain()

        assertEquals(1, report.completed)
        assertTrue(provider.submittedPlays.isEmpty())
        assertTrue(sink.entries.single().contains("unknown action"))
    }

    @Test
    fun `missing payload value drops the task`() = runTest {
        val drain = drainer()
        store.add("favorite", OutboxActionPayload())

        val report = drain.drain()

        assertEquals(1, report.completed)
        assertTrue(provider.favorites.isEmpty())
    }
}
