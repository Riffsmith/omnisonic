package app.omnisonic.core.data.history

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings.Options
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.repository.PlayCounter
import app.omnisonic.core.data.repository.SongRepository
import app.omnisonic.provider.api.OutboxActionPayload
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryEditorTest : DatabaseTestBase() {

    private val payloadAdapter = OmnisonicJson.moshi.adapter(OutboxActionPayload::class.java)

    private val snapshot = SongRepository.SongSnapshot(
        title = "Unsynced title",
        artistName = "Unsynced artist",
        coverImageId = "cover-1",
        durationMs = 200_000,
    )

    private fun outboxQueue() = OutboxQueue(
        db,
        db.outboxDao(),
        traitsSource = { ProviderTraits() },
        moshi = OmnisonicJson.moshi,
    )

    private fun counter(
        settings: PlayAggregateSettings = PlayAggregateSettings(),
        gate: HistoryRecordingGate = HistoryRecordingGate(),
    ) = PlayCounter(
        db = db,
        songRepository = SongRepository(db),
        outboxQueue = outboxQueue(),
        aggregateSettings = settings,
        recordingGate = gate,
    )

    private fun editor(
        settings: PlayAggregateSettings = PlayAggregateSettings(),
        gate: HistoryRecordingGate = HistoryRecordingGate(),
    ) = HistoryEditor(
        db = db,
        outboxQueue = outboxQueue(),
        aggregateSettings = settings,
        recordingGate = gate,
    )

    private suspend fun playCountPayload(providerId: Long, externalId: String): OutboxActionPayload? =
        outboxDao.selectTarget(providerId, ProviderMediaType.SONG.name, externalId, OutboxActions.PLAY_COUNT)
            ?.payloadJson?.let { payloadAdapter.fromJson(it) }

    @Test
    fun deletePlayDecreasesCounterAndOutboxDelta() = runDbTest {
        newProvider(id = 1)
        val counter = counter()
        counter.recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        counter.recordPlay(1, "song-1", snapshot, playedAtMs = 2_000, durationMs = 2_000)
        val events = playEventDao.selectByProviderAndExternal(1, "song-1")
        assertThat(events).hasSize(2)

        assertThat(editor().deleteEvent(events.first().id)).isTrue()

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.playCount).isEqualTo(1)
        assertThat(song.lastPlayed).isEqualTo(2_000)
        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1").map { it.playedAtMs })
            .containsExactly(2_000L)
        val payload = playCountPayload(1, "song-1")!!
        assertThat(payload.playCountDelta).isEqualTo(1)
        assertThat(payload.playTimestampsForSubmission()).containsExactly(2_000L)
    }

    @Test
    fun deleteLastPlayDropsTheOutboxRow() = runDbTest {
        newProvider(id = 1)
        counter().recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        val event = playEventDao.selectByProviderAndExternal(1, "song-1").single()

        assertThat(editor().deleteEvent(event.id)).isTrue()

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.playCount).isEqualTo(0)
        assertThat(song.lastPlayed).isNull()
        assertThat(playEventDao.selectByProvider(1)).isEmpty()
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun deleteSkipEventOnlyRemovesTheRow() = runDbTest {
        newProvider(id = 1)
        val counter = counter()
        counter.recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        val songId = counter.recordSkip(1, "song-1", snapshot, playedAtMs = 3_000, durationMs = 2_000)!!
        val skip = playEventDao.selectByProviderAndExternal(1, "song-1").single { it.skipped }

        assertThat(editor().deleteEvent(skip.id)).isTrue()

        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1")).hasSize(1)
        val song = songDao.selectById(songId)!!
        assertThat(song.playCount).isEqualTo(1)
        assertThat(song.skipped).isTrue()
        assertThat(outboxDao.selectByProvider(1)).hasSize(1)
    }

    @Test
    fun deleteEventWithPrunedSongRowStillRemovesTheEvent() = runDbTest {
        newProvider(id = 1)
        val songId = counter().recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)!!
        val event = playEventDao.selectByProviderAndExternal(1, "song-1").single()
        songDao.deleteById(songId)

        assertThat(editor().deleteEvent(event.id)).isTrue()

        assertThat(playEventDao.selectByProvider(1)).isEmpty()
        assertThat(songDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun deleteUnknownEventReturnsFalse() = runDbTest {
        newProvider(id = 1)

        assertThat(editor().deleteEvent(eventId = 999)).isFalse()
        assertThat(playEventDao.count()).isEqualTo(0)
    }

    @Test
    fun deleteRecomputesAggregatesCountOnlyAndKeepsResumeTrackPath() = runDbTest {
        val settings = PlayAggregateSettings().apply { update(Options(albumCountRule = AlbumCountRule.SUM)) }
        newProvider(id = 1)
        newAlbum(id = 10, providerId = 1)
        newSong(id = 11, providerId = 1, albumId = 10, filePath = "/a/one.m4a")
        newSong(id = 12, providerId = 1, albumId = 10, playCount = 3)
        newArtist(id = 99, providerId = 1)
        linkSongArtist(providerId = 1, artistId = 99, songId = 11)
        val counter = counter(settings = settings)
        counter.recordResumePoint(1, "song-11", snapshot.copy(albumExternalId = "alb-10"), positionMs = 42_000, now = 5_000)
        counter.recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        counter.recordPlay(1, "song-11", snapshot, playedAtMs = 2_000, durationMs = 2_000)
        assertThat(albumDao.selectById(10)!!.playCount).isEqualTo(5)
        assertThat(albumDao.selectById(10)!!.resumeTrackPath).isEqualTo("/a/one.m4a")

        val first = playEventDao.selectByProviderAndExternal(1, "song-11").first()
        assertThat(editor(settings = settings).deleteEvent(first.id)).isTrue()

        assertThat(albumDao.selectById(10)!!.playCount).isEqualTo(4)
        assertThat(albumDao.selectById(10)!!.lastPlayed).isEqualTo(2_000)
        assertThat(albumDao.selectById(10)!!.resumeTrackPath).isEqualTo("/a/one.m4a")
        assertThat(artistDao.selectById(99)!!.playCount).isEqualTo(1)
        assertThat(artistDao.selectById(99)!!.lastPlayed).isEqualTo(2_000)
        assertThat(songDao.selectByProviderAndExternal(1, "song-11")!!.lastPlayed).isEqualTo(2_000)
    }

    @Test
    fun editClampsAtZeroClearsLastPlayedAndDropsTheOutboxRow() = runDbTest {
        newProvider(id = 1)
        val counter = counter()
        counter.recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        counter.recordPlay(1, "song-1", snapshot, playedAtMs = 2_000, durationMs = 2_000)

        editor().setPlayCount(1, "song-1", count = -5)

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.playCount).isEqualTo(0)
        assertThat(song.lastPlayed).isNull()
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
        assertThat(playEventDao.selectByProvider(1)).hasSize(2)
    }

    @Test
    fun editRaisesCountAndAdjustsTheOutboxDelta() = runDbTest {
        newProvider(id = 1)
        val counter = counter()
        counter.recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        counter.recordPlay(1, "song-1", snapshot, playedAtMs = 2_000, durationMs = 2_000)

        editor().setPlayCount(1, "song-1", count = 5)

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.playCount).isEqualTo(5)
        assertThat(song.lastPlayed).isEqualTo(2_000)
        val payload = playCountPayload(1, "song-1")!!
        assertThat(payload.playCountDelta).isEqualTo(5)
        assertThat(payload.playTimestampsForSubmission()).hasSize(5)
    }

    @Test
    fun editUnknownSongIsANoOp() = runDbTest {
        newProvider(id = 1)

        editor().setPlayCount(1, "song-missing", count = 3)

        assertThat(songDao.selectByProvider(1)).isEmpty()
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun wipeClearsOnlyThatProvider() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        newAlbum(id = 10, providerId = 1)
        newSong(id = 11, providerId = 1, albumId = 10, filePath = "/a/one.m4a", resumeMs = 42_000)
        newSong(id = 12, providerId = 1, albumId = 10)
        newArtist(id = 99, providerId = 1)
        linkSongArtist(providerId = 1, artistId = 99, songId = 11)
        newAlbum(id = 20, providerId = 2)
        newSong(id = 21, providerId = 2, albumId = 20)
        songDao.setUserRating(11, 4)
        songDao.setFavorite(11, favorite = true, now = 1_000)
        val counter = counter()
        counter.recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        counter.recordPlay(1, "song-11", snapshot, playedAtMs = 2_000, durationMs = 2_000)
        counter.recordSkip(1, "song-12", snapshot, playedAtMs = 3_000, durationMs = 2_000)
        counter.recordPlay(2, "song-21", snapshot, playedAtMs = 1_500, durationMs = 2_000)
        outboxQueue().enqueueMediaChange(
            providerId = 1,
            mediaType = ProviderMediaType.SONG,
            externalId = "song-11",
            action = OutboxActions.FAVORITE,
            payload = OutboxActionPayload(isFavorite = true),
            now = 4_000,
        )
        assertThat(albumDao.selectById(10)!!.playCount).isEqualTo(1)
        assertThat(albumDao.selectById(10)!!.resumeTrackPath).isEqualTo("/a/one.m4a")

        editor().wipeProvider(1)

        assertThat(playEventDao.countByProvider(1)).isEqualTo(0)
        assertThat(playEventDao.countByProvider(2)).isEqualTo(1)
        val wiped = songDao.selectById(11)!!
        assertThat(wiped.playCount).isEqualTo(0)
        assertThat(wiped.lastPlayed).isNull()
        assertThat(wiped.skipped).isFalse()
        assertThat(wiped.lastSkipped).isNull()
        assertThat(wiped.resumeMs).isEqualTo(42_000)
        assertThat(wiped.userRating).isEqualTo(4)
        assertThat(wiped.isFavorite).isTrue()
        assertThat(songDao.selectById(12)!!.skipped).isFalse()
        assertThat(songDao.selectById(21)!!.playCount).isEqualTo(1)
        val album = albumDao.selectById(10)!!
        assertThat(album.playCount).isEqualTo(0)
        assertThat(album.lastPlayed).isNull()
        assertThat(album.resumeTrackPath).isEqualTo("/a/one.m4a")
        assertThat(artistDao.selectById(99)!!.playCount).isEqualTo(0)
        assertThat(outboxDao.selectByProvider(1).map { it.action }).containsExactly(OutboxActions.FAVORITE)
        assertThat(outboxDao.selectByProvider(2).map { it.action }).containsExactly(OutboxActions.PLAY_COUNT)
    }

    @Test
    fun wipeUnknownProviderIsANoOp() = runDbTest {
        newProvider(id = 1)
        counter().recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        editor().wipeProvider(99)

        assertThat(playEventDao.countByProvider(1)).isEqualTo(1)
        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.playCount).isEqualTo(1)
        assertThat(outboxDao.selectByProvider(1)).hasSize(1)
    }

    @Test
    fun pauseStateFlowsThroughTheEditor() = runDbTest {
        val gate = HistoryRecordingGate()
        val editor = editor(gate = gate)
        assertThat(editor.isRecordingPaused(1)).isFalse()
        assertThat(editor.pausedProviders.value).isEmpty()

        editor.setRecordingPaused(1, paused = true)
        assertThat(editor.isRecordingPaused(1)).isTrue()
        assertThat(editor.pausedProviders.value).containsExactly(1L)

        editor.setRecordingPaused(1, paused = false)
        assertThat(editor.isRecordingPaused(1)).isFalse()
        assertThat(editor.pausedProviders.value).isEmpty()
    }
}
