package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticRuleReactionTest : DatabaseTestBase() {

    private var now = 10_000L

    @Test
    fun `every watched table emits a change`() = reactionTest {
        newProvider(1)
        val observer = AutomaticRuleChangeObserver(db.automaticRuleChangeDao())
        val received = Channel<AutomaticRuleChangeSource>(Channel.UNLIMITED)
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            observer.observe().collect { received.send(it) }
        }
        try {
            rules().add(1, AutomaticRuleKind.FAVORITES)
            assertThat(awaitSource(received, AutomaticRuleChangeSource.RULE_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.RULE_ROWS)

            rules().setVariant(
                providerId = 1,
                kind = AutomaticRuleKind.FAVORITES,
                targetExternalId = "",
                transcodeBitrateKbps = 192,
                format = "mp3",
                scope = ProfileScope.Unrestricted,
            )
            assertThat(awaitSource(received, AutomaticRuleChangeSource.RULE_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.RULE_ROWS)

            val album = newAlbum(1, 1)
            albumDao.upsert(album.copy(title = "renamed album"))
            assertThat(awaitSource(received, AutomaticRuleChangeSource.ALBUM_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.ALBUM_ROWS)

            val song = seedSong(1, 1)
            songDao.upsert(song.copy(isFavorite = true))
            assertThat(awaitSource(received, AutomaticRuleChangeSource.SONG_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.SONG_ROWS)

            val playlist = seedPlaylist(1)
            playlistEntryDao.upsert(entry(playlist.id, 0, "entry-1"))
            assertThat(awaitSource(received, AutomaticRuleChangeSource.PLAYLIST_ENTRIES))
                .isEqualTo(AutomaticRuleChangeSource.PLAYLIST_ENTRIES)
            playlistEntryDao.upsert(entry(playlist.id, 1, "entry-2"))
            assertThat(awaitSource(received, AutomaticRuleChangeSource.PLAYLIST_ENTRIES))
                .isEqualTo(AutomaticRuleChangeSource.PLAYLIST_ENTRIES)
            playlistEntryDao.deleteByPlaylist(playlist.id)
            assertThat(awaitSource(received, AutomaticRuleChangeSource.PLAYLIST_ENTRIES))
                .isEqualTo(AutomaticRuleChangeSource.PLAYLIST_ENTRIES)

            rules().remove(1, AutomaticRuleKind.FAVORITES, "")
            assertThat(awaitSource(received, AutomaticRuleChangeSource.RULE_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.RULE_ROWS)

            seedDownloadItem(providerId = 1, mediaKey = "queue-key")
            assertThat(awaitSource(received, AutomaticRuleChangeSource.QUEUE_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.QUEUE_ROWS)
            downloadDao.deleteAll()
            assertThat(awaitSource(received, AutomaticRuleChangeSource.QUEUE_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.QUEUE_ROWS)

            offlineFileDao.upsert(
                OfflineFileEntity(
                    providerId = 1,
                    mediaType = "SONG",
                    sourceKey = "offline-key",
                    offlinePath = "OfflineMedias/aa/offline-key.flac",
                    sizeBytes = 4,
                    downloadedAt = now,
                ),
            )
            assertThat(awaitSource(received, AutomaticRuleChangeSource.OFFLINE_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.OFFLINE_ROWS)
            offlineFileDao.deleteBySourceKey("offline-key")
            assertThat(awaitSource(received, AutomaticRuleChangeSource.OFFLINE_ROWS))
                .isEqualTo(AutomaticRuleChangeSource.OFFLINE_ROWS)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a favorites rule expands on add and empties on remove without deleting rows`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness(changes = observedChanges())
        val session = harness.start()
        val baseKey = DownloadKeys.mediaKey(1, "song-1", null, null)
        val variantKey = DownloadKeys.mediaKey(1, "song-1", 192, "mp3")
        try {
            harness.awaitVerified { it.wantedMediaKeys == setOf(baseKey) }
            assertThat(downloadDao.selectByMediaKey(baseKey)).isNotNull()

            rules().setVariant(1, AutomaticRuleKind.FAVORITES, "", 192, "mp3", ProfileScope.Unrestricted)
            harness.awaitVerified { it.wantedMediaKeys == setOf(variantKey) }
            assertThat(downloadDao.selectByMediaKey(variantKey)).isNotNull()

            rules().remove(1, AutomaticRuleKind.FAVORITES, "")
            harness.awaitVerified { it.ruleCount == 0 && it.wantedMediaKeys.isEmpty() }
            assertThat(downloadDao.selectByProvider(1)).hasSize(2)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a playlist rule follows added entries and keeps removed entries`() = reactionTest {
        newProvider(1)
        val first = seedSong(1, 1)
        val second = seedSong(2, 1)
        val playlist = playlistWithCount(1, 1)
        addEntry(playlist.id, 0, first)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
        val harness = Harness(changes = observedChanges())
        val session = harness.start()
        val firstKey = DownloadKeys.mediaKey(1, "song-1", null, null)
        val secondKey = DownloadKeys.mediaKey(1, "song-2", null, null)
        try {
            harness.awaitVerified { it.wantedMediaKeys == setOf(firstKey) }

            playlistDao.upsert(playlist.copy(entryCount = 2))
            addEntry(playlist.id, 1, second)
            harness.awaitVerified { it.wantedMediaKeys == setOf(firstKey, secondKey) }
            assertThat(downloadDao.selectByProvider(1)).hasSize(2)

            rules().remove(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
            harness.awaitVerified { it.ruleCount == 0 && it.wantedMediaKeys.isEmpty() }
            assertThat(downloadDao.selectByProvider(1)).hasSize(2)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `an album rule reacts to a track without a manual pass request`() = reactionTest {
        newProvider(1)
        val album = albumWithCount(1, 1)
        seedSong(1, 1, albumId = album.id, trackNo = 1)
        rules().add(1, AutomaticRuleKind.ALBUM, album.externalId!!)
        val harness = Harness(changes = observedChanges())
        val session = harness.start()
        val firstKey = DownloadKeys.mediaKey(1, "song-1", null, null)
        val secondKey = DownloadKeys.mediaKey(1, "song-2", null, null)
        try {
            harness.awaitVerified { it.wantedMediaKeys == setOf(firstKey) }

            seedSong(2, 1, albumId = album.id, trackNo = 2)
            albumDao.upsert(album.copy(songCount = 2))
            harness.awaitVerified { it.wantedMediaKeys == setOf(firstKey, secondKey) }

            rules().remove(1, AutomaticRuleKind.ALBUM, album.externalId!!)
            harness.awaitVerified { it.ruleCount == 0 && it.wantedMediaKeys.isEmpty() }
            assertThat(downloadDao.selectByProvider(1)).hasSize(2)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a favorite edit triggers a fresh pass without a manual request`() = reactionTest {
        newProvider(1)
        val song = seedSong(1, 1, favorite = false)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness(changes = observedChanges())
        val session = harness.start()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        try {
            harness.awaitVerified { it.wantedMediaKeys.isEmpty() }

            songDao.upsert(song.copy(isFavorite = true))

            harness.awaitVerified { it.wantedMediaKeys == setOf(key) }
            assertThat(downloadDao.selectByMediaKey(key)).isNotNull()
        } finally {
            session.stop()
        }
    }

    @Test
    fun `sync completion requests a fresh generation without a table emission`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            val initial = harness.awaitVerified { it.ruleCount == 1 }

            harness.sync.tryBegin(1)
            harness.sync.finish(1)

            harness.awaitVerified { it.sequence > initial.sequence }
        } finally {
            session.stop()
        }
    }

    @Test
    fun `overlapping rules resolve to one identity while both stay current`() = reactionTest {
        newProvider(1)
        val song = seedSong(1, 1, favorite = true)
        val playlist = playlistWithCount(1, 1)
        addEntry(playlist.id, 0, song)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
        val harness = Harness(changes = observedChanges())
        val session = harness.start()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        try {
            val generation = harness.awaitVerified {
                it.ruleCount == 2 && it.wantedMediaKeys == setOf(key)
            }
            assertThat(generation.expansions.map { it.acceptedCount }).containsExactly(1, 1)
            assertThat(downloadDao.selectByProvider(1)).hasSize(1)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a wanted track already offline is not enqueued again`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "SONG",
                sourceKey = key,
                offlinePath = "OfflineMedias/aa/song-1.flac",
                sizeBytes = 8,
                externalId = "song-1",
                tempCache = true,
                downloadedAt = now,
            ),
        )
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            val generation = harness.awaitVerified { it.wantedMediaKeys == setOf(key) }
            assertThat(generation.expansions.single().alreadyPresentCount).isEqualTo(1)
            assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()

            val settledResolves = harness.source.resolves
            delay(150)
            assertThat(harness.source.resolves).isEqualTo(settledResolves)

            offlineFileDao.deleteBySourceKey(key)
            harness.coordinator.requestPass()
            awaitUntil { downloadDao.selectByMediaKey(key) != null }
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a sync running at pass start yields and recomputes after it finishes`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        harness.sync.tryBegin(1)
        val session = harness.start()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        try {
            harness.coordinator.requestPass()
            harness.awaitStale(AutomaticRuleGenerationStaleReason.SYNC_RUNNING)
            assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()

            harness.sync.finish(1)
            harness.awaitVerified { it.wantedMediaKeys == setOf(key) }
            assertThat(downloadDao.selectByMediaKey(key)).isNotNull()
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a sync starting mid-pass blocks hand-off and recomputes fresh`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        harness.source.hold()
        val session = harness.start()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        try {
            harness.coordinator.requestPass()
            harness.source.entered.receive()

            harness.sync.tryBegin(1)
            harness.source.release()
            harness.awaitStale(AutomaticRuleGenerationStaleReason.SYNC_RUNNING)

            harness.sync.finish(1)
            harness.awaitVerified { it.wantedMediaKeys == setOf(key) }
            assertThat(harness.source.resolves).isEqualTo(2)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a rule added mid-pass is not published as the current generation`() = reactionTest {
        newProvider(1)
        val favorite = seedSong(1, 1, favorite = true)
        val playlist = playlistWithCount(1, 1)
        addEntry(playlist.id, 0, favorite)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        harness.source.hold()
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            harness.source.entered.receive()

            rules().add(1, AutomaticRuleKind.PLAYLIST, playlist.externalId!!)
            harness.source.release(holdNext = true)

            harness.awaitStale(AutomaticRuleGenerationStaleReason.RULES_CHANGED)
            harness.source.entered.receive()
            harness.source.release()
            val generation = harness.awaitVerified {
                it.ruleCount == 2 && it.wantedMediaKeys.size == 1
            }
            assertThat(generation.expansions.map { it.rule.providerId }).containsExactly(1L, 1L)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `an empty valid rule is verified while a failed read stays stale`() = reactionTest {
        newProvider(1)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            val empty = harness.awaitVerified { it.ruleCount == 1 && it.wantedMediaKeys.isEmpty() }

            seedSong(1, 1, favorite = true)
            harness.source.fail = true
            harness.coordinator.requestPass()
            val stale = harness.awaitStale(AutomaticRuleGenerationStaleReason.INCOMPLETE_EXPANSION)

            assertThat(stale.verified).isFalse()
            assertThat(stale.generation).isEqualTo(empty)
            assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()
        } finally {
            session.stop()
        }
    }

    @Test
    fun `hidden providers stay in the unrestricted internal wanted set`() = reactionTest {
        newProvider(1)
        newProvider(2)
        seedSong(1, 1, favorite = true)
        seedSong(2, 2, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        rules().add(2, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            val generation = harness.awaitVerified { it.providerIds == setOf(1L, 2L) }
            assertThat(generation.wantedMediaKeys).containsExactly(
                DownloadKeys.mediaKey(1, "song-1", null, null),
                DownloadKeys.mediaKey(2, "song-2", null, null),
            )
            assertThat(downloadDao.selectByProvider(2)).hasSize(1)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a large snapshot reports every wanted identity`() = reactionTest {
        newProvider(1)
        val total = 505
        songDao.upsertRows(
            (1..total).map { id ->
                SongEntity(
                    id = id.toLong(),
                    providerId = 1,
                    externalId = "song-$id",
                    title = "Song $id",
                    isFavorite = true,
                    updatedAt = now,
                )
            },
        )
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            val generation = harness.awaitVerified { it.wantedMediaKeys.size == total }
            assertThat(generation.wantedMediaKeys).hasSize(total)
            assertThat(downloadDao.selectByProvider(1)).hasSize(total)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `a burst of triggers runs one extra coalesced pass at most`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        harness.source.hold()
        val session = harness.start()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        try {
            harness.coordinator.requestPass()
            harness.source.entered.receive()

            repeat(20) { harness.coordinator.requestPass() }
            harness.source.release()

            harness.awaitVerified { it.wantedMediaKeys == setOf(key) }
            awaitUntil { harness.source.resolves == 2 }
            delay(100)
            assertThat(harness.source.resolves).isEqualTo(2)
            assertThat(harness.source.maxActive).isEqualTo(1)
        } finally {
            session.stop()
        }
    }

    @Test
    fun `concurrent passes are serialized`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        harness.source.hold()
        try {
            coroutineScope {
                val first = launch(Dispatchers.IO) { harness.coordinator.runPass() }
                harness.source.entered.receive()
                val second = launch(Dispatchers.IO) { harness.coordinator.runPass() }
                delay(50)
                assertThat(harness.source.resolves).isEqualTo(1)

                harness.source.release()
                joinAll(first, second)
            }
            assertThat(harness.source.maxActive).isEqualTo(1)
            assertThat(harness.source.resolves).isEqualTo(2)
        } finally {
            harness.source.release()
        }
    }

    @Test
    fun `cancellation stops an in-flight pass`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        harness.source.hold()
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            harness.source.entered.receive()

            session.stop()
            assertThat(session.job.isCancelled).isTrue()
            harness.source.release()
            delay(50)
            assertThat(harness.source.resolves).isEqualTo(1)
        } finally {
            session.stop()
            harness.source.release()
        }
    }

    @Test
    fun `cancellation stops the loop after a sync yield`() = reactionTest {
        newProvider(1)
        seedSong(1, 1, favorite = true)
        rules().add(1, AutomaticRuleKind.FAVORITES)
        val harness = Harness()
        harness.sync.tryBegin(1)
        val session = harness.start()
        try {
            harness.coordinator.requestPass()
            harness.awaitStale(AutomaticRuleGenerationStaleReason.SYNC_RUNNING)

            session.stop()
            assertThat(session.job.isCancelled).isTrue()
        } finally {
            harness.sync.finish(1)
            session.stop()
        }
    }

    private fun observedChanges(): Flow<AutomaticRuleChangeSource> =
        AutomaticRuleChangeObserver(db.automaticRuleChangeDao()).observe()

    private fun reactionTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        db = OmnisonicDatabaseFactory(appContext).openInMemory()
        try {
            coroutineScope(block)
        } finally {
            db.close()
        }
    }

    private fun rules() = AutomaticRuleRepository(db, nowMs = { now })

    private fun enqueuer(): DownloadEnqueuer {
        val coordinator = DownloadWorkerCoordinator(
            repository = DownloadQueueRepository(downloadDao),
            settings = DownloadQueueSettingsStore.inMemory(),
            runGate = ProviderRunGate { ProviderStatus.OK },
            workerCount = 2,
            nowMs = { now },
        )
        return DownloadEnqueuer(
            db = db,
            coordinator = coordinator,
            traitsSource = ProviderTraitsSource { ProviderTraits() },
            runGate = ProviderRunGate { ProviderStatus.OK },
            nowMs = { now },
        )
    }

    private inner class Harness(
        changes: Flow<AutomaticRuleChangeSource> = emptyFlow(),
        val source: CountingSource = CountingSource(
            DatabaseAutomaticRuleTrackSource(db.automaticRuleCatalogDao()),
        ),
        val sync: LibrarySyncStatusRepository = LibrarySyncStatusRepository(),
        val store: AutomaticRuleGenerationStore = AutomaticRuleGenerationStore(),
    ) {
        val coordinator = AutomaticRuleReactionCoordinator(
            rules = rules(),
            expander = AutomaticRuleExpander(rules(), source, enqueuer()),
            changes = changes,
            syncStatus = sync,
            generations = store,
            offline = db.offlineFileDao(),
            nowMs = { now },
            syncCompletions = sync.completions,
        )

        fun start(): ReactionSession {
            val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { coordinator.run() }
            return ReactionSession(job)
        }

        suspend fun awaitVerified(
            timeoutMs: Long = 15_000,
            predicate: (AutomaticRuleGeneration) -> Boolean,
        ): AutomaticRuleGeneration {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val state = store.state.value
                val generation = state.generation
                if (state.verified && generation != null && predicate(generation)) return generation
                check(System.currentTimeMillis() < deadline) { "no matching verified generation: $state" }
                delay(5)
            }
        }

        suspend fun awaitStale(
            reason: AutomaticRuleGenerationStaleReason,
            timeoutMs: Long = 15_000,
        ): AutomaticRuleGenerationState {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val state = store.state.value
                if (!state.verified && state.staleReason == reason) return state
                check(System.currentTimeMillis() < deadline) { "generation never became $reason: $state" }
                delay(5)
            }
        }
    }

    private class ReactionSession(val job: Job) {
        suspend fun stop() {
            job.cancelAndJoin()
        }
    }

    private class CountingSource(
        private val delegate: AutomaticRuleTrackSource,
    ) : AutomaticRuleTrackSource {

        private val lock = Any()
        private var active = 0
        private var holding: CompletableDeferred<Unit>? = null

        val entered = Channel<Unit>(Channel.UNLIMITED)

        @Volatile
        var fail = false

        var resolves = 0
            private set

        var maxActive = 0
            private set

        fun hold() {
            holding = CompletableDeferred()
        }

        fun release(holdNext: Boolean = false) {
            val current = holding
            holding = if (holdNext) CompletableDeferred() else null
            current?.complete(Unit)
        }

        override suspend fun resolve(
            rule: AutomaticRule,
            scope: ProfileScope,
        ): AutomaticRuleTargetResolution {
            synchronized(lock) {
                resolves++
                active++
                maxActive = maxOf(maxActive, active)
            }
            entered.send(Unit)
            try {
                holding?.await()
                if (fail) error("catalog unavailable")
                return delegate.resolve(rule, scope)
            } finally {
                synchronized(lock) { active-- }
            }
        }
    }

    private suspend fun seedSong(
        id: Long,
        providerId: Long,
        albumId: Long? = null,
        externalId: String = "song-$id",
        favorite: Boolean = false,
        trackNo: Int? = null,
    ): SongEntity {
        val song = SongEntity(
            id = id,
            providerId = providerId,
            externalId = externalId,
            albumId = albumId,
            title = "Song $id",
            isFavorite = favorite,
            trackNo = trackNo,
            updatedAt = now,
        )
        songDao.upsert(song)
        return songDao.selectById(id) ?: error("song row missing")
    }

    private suspend fun playlistWithCount(providerId: Long, count: Int): PlaylistEntity {
        val playlist = seedPlaylist(providerId)
        playlistDao.upsert(playlist.copy(entryCount = count))
        return playlistDao.selectById(playlist.id) ?: error("playlist row missing")
    }

    private suspend fun albumWithCount(providerId: Long, count: Int) = newAlbum(1, providerId).let {
        albumDao.upsert(it.copy(songCount = count))
        albumDao.selectById(it.id) ?: error("album row missing")
    }

    private suspend fun addEntry(playlistId: Long, position: Int, song: SongEntity) {
        playlistEntryDao.upsert(entry(playlistId, position, song.externalId!!, song.id))
    }

    private fun entry(
        playlistId: Long,
        position: Int,
        externalId: String,
        songId: Long? = null,
    ) = PlaylistEntryEntity(
        playlistId = playlistId,
        providerId = 1,
        position = position,
        externalId = externalId,
        songId = songId,
        title = externalId,
        addedAt = now,
    )

    private suspend fun awaitUntil(
        timeoutMs: Long = 15_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition was not met in time" }
            delay(5)
        }
    }

    private suspend fun awaitSource(
        channel: ReceiveChannel<AutomaticRuleChangeSource>,
        expected: AutomaticRuleChangeSource,
    ): AutomaticRuleChangeSource = withTimeout(15_000) {
        while (true) {
            val source = channel.receive()
            if (source == expected) return@withTimeout source
        }
        error("unreachable")
    }
}
