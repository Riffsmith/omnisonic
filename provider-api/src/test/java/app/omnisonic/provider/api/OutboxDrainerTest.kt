package app.omnisonic.provider.api

import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import com.squareup.moshi.Moshi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    data class TrackedFailure(val id: Long, val attempts: Int, val nextAttemptAt: Long, val lastError: String?)
    data class TrackedProgress(val id: Long, val payloadJson: String?)

    override suspend fun dueTasks(now: Long, providerId: Long?): List<OutboxTask> = tasks.toList()

    override suspend fun complete(task: OutboxTask): OutboxCompletion {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return OutboxCompletion.Missing
        if (tasks[index].payloadJson != task.payloadJson) return OutboxCompletion.Superseded
        completed += task.id
        tasks.removeAt(index)
        return OutboxCompletion.Deleted
    }

    override suspend fun fail(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String) {
        failures += TrackedFailure(id, attempts, nextAttemptAt, lastError)
        tasks.removeAll { it.id == id }
    }

    override suspend fun progress(
        task: OutboxTask,
        submittedTimestamps: List<Long>,
        nextAttemptAt: Long,
    ): OutboxProgress {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index < 0) return OutboxProgress.Cleared
        val current = tasks[index]
        val payload = current.payloadJson?.let { payloadAdapter.fromJson(it) } ?: OutboxActionPayload()
        val remaining = payload.afterSubmitting(submittedTimestamps)
        val remainingTimestamps = remaining.playTimestamps
        if (remainingTimestamps.isNullOrEmpty()) {
            progressed += TrackedProgress(task.id, null)
            tasks.removeAt(index)
            return OutboxProgress.Cleared
        }
        val encoded = payloadAdapter.toJson(remaining)
        progressed += TrackedProgress(task.id, encoded)
        tasks[index] = current.copy(payloadJson = encoded)
        return OutboxProgress.Retained(remainingTimestamps.size)
    }

    fun add(
        action: String,
        payload: OutboxActionPayload,
        attempts: Int = 0,
        mediaType: String = ProviderMediaType.SONG.name,
    ): OutboxTask {
        val encoded = if (defaultValued(payload)) null else moshi.adapter(OutboxActionPayload::class.java).toJson(payload)
        val task = OutboxTask(
            id = (tasks.size + 1L),
            providerId = 7L,
            mediaType = mediaType,
            externalId = "song-1",
            action = action,
            attempts = attempts,
            payloadJson = encoded,
        )
        tasks += task
        return task
    }

    fun addRaw(task: OutboxTask) {
        tasks += task
    }

    private fun defaultValued(payload: OutboxActionPayload) =
        payload == OutboxActionPayload()
}

private val moshi: Moshi = Moshi.Builder().build()

private val payloadAdapter = moshi.adapter(OutboxActionPayload::class.java)

class OutboxDrainerTest {

    private val store = FakeOutboxStore()
    private val provider = RecordingProvider()
    private val sink = CollectingSink()

    private fun drainer(
        clock: () -> Long = { 1_000L },
        runGate: ProviderRunGate = AllowAllRunGate,
        onAuthFailed: suspend (Long) -> Unit = {},
        onAuthSucceeded: suspend (Long) -> Unit = {},
    ): OutboxDrainer = OutboxDrainer(
        store = store,
        providerLocator = { provider },
        clock = clock,
        runGate = runGate,
        logger = OmnisonicLogger(sink),
        onAuthFailed = onAuthFailed,
        onAuthSucceeded = onAuthSucceeded,
    )

    @Test
    fun `blocked run gate defers the whole drain without touching tasks`() = runTest {
        val drain = drainer(runGate = ProviderRunGate { ProviderStatus.NEEDS_WIFI })
        store.add("play_count", OutboxActionPayload(playCountDelta = 1, playTimestamps = listOf(1_000L)))

        val report = drain.drain(providerId = 7)

        assertEquals(0, report.completed)
        assertEquals(0, report.deferred)
        assertEquals(ProviderStatus.NEEDS_WIFI, report.blockedBy)
        assertEquals(1, store.tasks.size)
        assertTrue(provider.submittedPlays.isEmpty())
    }

    @Test
    fun `offline run gate reports the offline status`() = runTest {
        val drain = drainer(runGate = ProviderRunGate { ProviderStatus.OFFLINE })
        store.add("favorite", OutboxActionPayload(isFavorite = true))

        val report = drain.drain(providerId = 7)

        assertEquals(ProviderStatus.OFFLINE, report.blockedBy)
        assertEquals(1, store.tasks.size)
    }

    @Test
    fun `global drain skips blocked providers and leaves their tasks due`() = runTest {
        val drain = drainer(runGate = ProviderRunGate { if (it == 7L) ProviderStatus.NEEDS_WIFI else ProviderStatus.OK })
        store.add("play_count", OutboxActionPayload(playCountDelta = 1, playTimestamps = listOf(1_000L)))

        val report = drain.drain()

        assertEquals(0, report.completed)
        assertEquals(0, report.deferred)
        assertNull(report.blockedBy)
        assertEquals(1, store.tasks.size)
    }

    @Test
    fun `play count delta fully submitted completes the task`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 3, playTimestamps = listOf(1_000L, 2_000L, 3_000L)))
        provider.submitPlaysResult = 3

        val report = drain.drain()

        assertEquals(1, report.completed)
        assertEquals(listOf(RecordingProvider.SubmittedPlay("song-1", listOf(1_000L, 2_000L, 3_000L))), provider.submittedPlays)
    }

    @Test
    fun `play count delta carries recorded last played timestamp`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 5_000L))
        provider.submitPlaysResult = 1

        drain.drain()

        assertEquals(listOf(5_000L), provider.submittedPlays.single().timestamps)
    }

    @Test
    fun `legacy payload without timestamps still submits one play per delta`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 3, lastPlayedAt = 5_000L))
        provider.submitPlaysResult = 3

        drain.drain()

        assertEquals(listOf(5_000L, 5_001L, 5_002L), provider.submittedPlays.single().timestamps)
    }

    @Test
    fun `partially submitted delta records progress without re-attempting accepted part`() = runTest {
        val drain = drainer()
        val task = store.add(
            "play_count",
            OutboxActionPayload(playCountDelta = 3, lastPlayedAt = 5_000L, playTimestamps = listOf(5_000L, 5_001L, 5_002L)),
        )
        provider.submitPlaysResult = 1

        val report = drain.drain()

        assertEquals(1, report.progressed)
        val remaining = store.progressed.single { it.id == task.id }.payloadJson
        assertEquals(
            OutboxActionPayload(playCountDelta = 2, lastPlayedAt = 5_001L, playTimestamps = listOf(5_001L, 5_002L)),
            remaining?.let { payloadAdapter.fromJson(it) },
        )
    }

    @Test
    fun `play count submits in chunks and records progress between them`() = runTest {
        val drain = drainer()
        val timestamps = (0L until 250L).map { 1_700_000_000_000L + it }
        val task = store.add(
            "play_count",
            OutboxActionPayload(
                playCountDelta = timestamps.size,
                lastPlayedAt = timestamps.last(),
                playTimestamps = timestamps,
            ),
        )
        provider.submitPlaysResults += listOf(100, 100, 50)

        val report = drain.drain()

        assertEquals(1, report.completed)
        assertEquals(listOf(100, 100, 50), provider.submittedPlays.map { it.timestamps.size })
        assertEquals(timestamps, provider.submittedPlays.flatMap { it.timestamps })
        val remainingSizes = store.progressed
            .filter { it.id == task.id }
            .map { progress ->
                progress.payloadJson?.let { payloadAdapter.fromJson(it) }?.playTimestamps?.size ?: 0
            }
        assertEquals(listOf(150, 50, 0), remainingSizes)
    }

    @Test
    fun `a rejected submission inside a chunk keeps every unsubmitted timestamp`() = runTest {
        val drain = drainer()
        val timestamps = (0L until 250L).map { 1_700_000_000_000L + it }
        val task = store.add(
            "play_count",
            OutboxActionPayload(
                playCountDelta = timestamps.size,
                lastPlayedAt = timestamps.last(),
                playTimestamps = timestamps,
            ),
        )
        provider.submitPlaysResults += listOf(100, 49)

        val report = drain.drain()

        assertEquals(1, report.progressed)
        assertEquals(listOf(100, 100), provider.submittedPlays.map { it.timestamps.size })
        val remaining = store.progressed
            .last { it.id == task.id }
            .payloadJson
            ?.let { payloadAdapter.fromJson(it) }
        assertEquals(101, remaining?.playCountDelta)
        assertEquals(timestamps.drop(149), remaining?.playTimestamps)
    }

    @Test
    fun `first play count task probes the provider exactly once per drainer`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 5_000L))
        provider.submitPlaysResult = 1

        drain.drain()

        assertEquals(1, provider.probed.size)

        store.add("play_count", OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 6_000L))
        provider.submitPlaysResult = 1
        drain.drain()

        assertEquals(1, provider.probed.size)
    }

    @Test
    fun `a failed probe is retried on the next drain`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 5_000L))
        provider.submitPlaysResult = 1
        provider.probeFailures = 1

        val first = drain.drain()

        assertEquals(1, provider.probed.size)
        assertEquals(1, first.completed)

        store.add("play_count", OutboxActionPayload(playCountDelta = 1, lastPlayedAt = 6_000L))
        provider.submitPlaysResult = 1
        drain.drain()

        assertEquals(2, provider.probed.size)
    }

    @Test
    fun `zero submissions defers with base backoff`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload(playCountDelta = 2, playTimestamps = listOf(1L, 2L)))
        provider.submitPlaysResult = 0

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
        store.add("resume_point", OutboxActionPayload(resumeMs = 42_000L))

        drain.drain()

        assertEquals(listOf("song-1" to 42_000L), provider.savedBookmarks)
    }

    @Test
    fun `resume point zero clears the bookmark`() = runTest {
        val drain = drainer()
        store.add("resume_point", OutboxActionPayload(resumeMs = 0L))

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
    fun `auth failure stops later tasks for the provider in the same drain`() = runTest {
        val authFailures = mutableListOf<Long>()
        val authProvider = object : MediaProvider by provider {
            override suspend fun submitPlays(externalId: String, timestamps: List<Long>): Int =
                throw ProviderAuthException("code=40 Wrong username or password")

            override suspend fun setFavorite(
                mediaType: ProviderMediaType,
                externalId: String,
                favorite: Boolean,
            ): Boolean = throw ProviderAuthException("code=40 Wrong username or password")
        }
        store.add("favorite", OutboxActionPayload(isFavorite = true))
        store.add("play_count", OutboxActionPayload(playCountDelta = 1, playTimestamps = listOf(1_000L)))
        val drain = OutboxDrainer(
            store = store,
            providerLocator = { authProvider },
            clock = { 1_000L },
            logger = OmnisonicLogger(sink),
            onAuthFailed = { authFailures += it },
        )

        val report = drain.drain(providerId = 7)

        assertEquals(1, report.deferred)
        assertEquals(2, store.tasks.size)
        assertTrue(store.failures.isEmpty())
        assertTrue(provider.submittedPlays.isEmpty())
        assertEquals(listOf(7L), authFailures)
    }

    @Test
    fun `an auth failed provider is not retried by the next drain`() = runTest {
        var status = ProviderStatus.OK
        val authProvider = object : MediaProvider by provider {
            override suspend fun setFavorite(
                mediaType: ProviderMediaType,
                externalId: String,
                favorite: Boolean,
            ): Boolean = throw ProviderAuthException("expired token")
        }
        store.add("favorite", OutboxActionPayload(isFavorite = true))
        val drain = OutboxDrainer(
            store = store,
            providerLocator = { authProvider },
            clock = { 1_000L },
            runGate = { status },
            logger = OmnisonicLogger(sink),
            onAuthFailed = { status = ProviderStatus.AUTH_FAILED },
        )

        val first = drain.drain(providerId = 7)
        val second = drain.drain(providerId = 7)

        assertEquals(1, first.deferred)
        assertEquals(ProviderStatus.AUTH_FAILED, second.blockedBy)
        assertEquals(1, store.tasks.size)
    }

    @Test
    fun `successful task reports authentication success`() = runTest {
        val successes = mutableListOf<Long>()
        val drain = drainer(onAuthSucceeded = { successes += it })
        store.add("favorite", OutboxActionPayload(isFavorite = true))

        drain.drain(providerId = 7)

        assertEquals(listOf(7L), successes)
    }

    @Test
    fun `generic provider failure does not report an auth failure`() = runTest {
        val authFailures = mutableListOf<Long>()
        val drain = drainer(onAuthFailed = { authFailures += it })
        store.add("favorite", OutboxActionPayload(isFavorite = true))
        provider.failNext = true

        drain.drain(providerId = 7)

        assertTrue(authFailures.isEmpty())
        assertEquals(1, store.failures.size)
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
    fun `missing payload value parks the task as failed instead of completing it`() = runTest {
        val drain = drainer()
        store.add("favorite", OutboxActionPayload())

        val report = drain.drain()

        assertEquals(1, report.deferred)
        assertEquals(0, report.completed)
        assertTrue(provider.favorites.isEmpty())
        val failure = store.failures.single()
        assertEquals("empty payload", failure.lastError)
        assertTrue(store.completed.isEmpty())
    }

    @Test
    fun `play count task without a delta parks as failed`() = runTest {
        val drain = drainer()
        store.add("play_count", OutboxActionPayload())

        val report = drain.drain()

        assertEquals(1, report.deferred)
        assertEquals("empty payload", store.failures.single().lastError)
        assertTrue(provider.submittedPlays.isEmpty())
    }

    @Test
    fun `unknown media type parks the task as failed without touching the provider`() = runTest {
        val drain = drainer()
        store.add("favorite", OutboxActionPayload(isFavorite = true), mediaType = "track")

        val report = drain.drain()

        assertEquals(1, report.deferred)
        assertEquals(0, report.completed)
        assertEquals("unknown media type: track", store.failures.single().lastError)
        assertTrue(provider.favorites.isEmpty())
        assertTrue(provider.submittedPlays.isEmpty())
    }

    @Test
    fun `resume point clear runs after a play count task for the same item`() = runTest {
        val drain = drainer()
        // The resume_point row is due first (lower createdAt), the play_count row later.
        val resume = OutboxTask(
            id = 1L,
            providerId = 7L,
            mediaType = ProviderMediaType.SONG.name,
            externalId = "song-1",
            action = "resume_point",
            attempts = 0,
            payloadJson = payloadAdapter.toJson(OutboxActionPayload(resumeMs = 0L)),
        )
        val play = OutboxTask(
            id = 2L,
            providerId = 7L,
            mediaType = ProviderMediaType.SONG.name,
            externalId = "song-1",
            action = "play_count",
            attempts = 0,
            payloadJson = payloadAdapter.toJson(OutboxActionPayload(playCountDelta = 1, playTimestamps = listOf(5_000L))),
        )
        store.addRaw(resume)
        store.addRaw(play)
        provider.submitPlaysResult = 1

        val report = drain.drain()

        assertEquals(2, report.completed)
        assertEquals(listOf("submitPlays", "deleteBookmark"), provider.events)
    }

    @Test
    fun `cancelling the drain mid-task records no failure and cancels the caller`() = runTest {
        val blockingStore = FakeOutboxStore()
        blockingStore.add("favorite", OutboxActionPayload(isFavorite = true))
        val blockedProvider = object : MediaProvider {
            override suspend fun probe(): ProviderCapabilities = ProviderCapabilities()
            override suspend fun listArtists(): List<ProviderArtist> = emptyList()
            override suspend fun browseDirectory(externalId: String): ProviderDirectory = ProviderDirectory(externalId)
            override suspend fun getAlbum(albumExternalId: String): ProviderAlbumDetails? = null
            override suspend fun songDetails(externalId: String): ProviderSong? = null
            override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> = emptyList()
            override suspend fun listGenres(): List<ProviderGenre> = emptyList()
            override suspend fun listRadios(): List<ProviderRadio> = emptyList()
            override suspend fun listPlaylists(): List<ProviderPlaylistSummary> = emptyList()
            override suspend fun getPlaylist(playlistExternalId: String): ProviderPlaylist? = null
            override suspend fun listBookmarks(): List<ProviderBookmark> = emptyList()
            override suspend fun searchArtists(query: String, offset: Int, count: Int): List<ProviderArtist> = emptyList()
            override suspend fun searchAlbums(query: String, offset: Int, count: Int): List<ProviderAlbum> = emptyList()
            override suspend fun searchSongs(query: String, offset: Int, count: Int): List<ProviderSong> = emptyList()
            override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null
            override fun coverArtUrl(imageId: String?, sizePx: Int?): String? = null
            override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null
            override suspend fun submitPlays(externalId: String, timestamps: List<Long>): Int = timestamps.size
            override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean = true
            override suspend fun setFavorite(mediaType: ProviderMediaType, externalId: String, favorite: Boolean): Boolean {
                awaitCancellation()
            }

            override suspend fun setUserRating(mediaType: ProviderMediaType, externalId: String, rating: Int): Boolean = true
            override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean = true
            override suspend fun deleteBookmark(externalId: String): Boolean = true
            override suspend fun similarSongs(externalId: String, count: Int): List<ProviderSong> = emptyList()
        }
        val drain = OutboxDrainer(
            store = blockingStore,
            providerLocator = { blockedProvider },
            clock = { 1_000L },
            logger = OmnisonicLogger(sink),
        )
        var drainReturned = false
        val job = launch {
            drain.drain()
            drainReturned = true
        }
        runCurrent()
        assertTrue(provider.favorites.isEmpty())

        job.cancelAndJoin()

        assertTrue(blockingStore.failures.isEmpty())
        assertTrue(!drainReturned)
        assertTrue(job.isCancelled)
    }
}
