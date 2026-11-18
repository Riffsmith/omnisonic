package app.omnisonic.core.data.repository

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.history.HistoryRecordingGate
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings.Options
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayCounterTest : DatabaseTestBase() {

    private fun counter(
        traits: ProviderTraits = ProviderTraits(),
        settings: PlayAggregateSettings = PlayAggregateSettings(),
        gate: HistoryRecordingGate = HistoryRecordingGate(),
    ) = PlayCounter(
        db = db,
        songRepository = SongRepository(db),
        outboxQueue = OutboxQueue(db, db.outboxDao(), traitsSource = { traits }, moshi = app.omnisonic.core.data.json.OmnisonicJson.moshi),
        aggregateSettings = settings,
        recordingGate = gate,
    )

    private val snapshot = SongRepository.SongSnapshot(
        title = "Unsynced title",
        artistName = "Unsynced artist",
        coverImageId = "cover-1",
        durationMs = 200_000,
    )

    @Test
    fun recordsAPlayForAnItemWithoutASyncedSong() = runDbTest {
        newProvider(id = 1)

        val songId = counter().recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)!!

        val song = songDao.selectById(songId)!!
        assertThat(song.title).isEqualTo("Unsynced title")
        assertThat(song.playCount).isEqualTo(1)
        assertThat(song.lastPlayed).isEqualTo(1_000)
        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1")).hasSize(1)
        assertThat(outboxDao.selectByProvider(1)).hasSize(1)
    }

    @Test
    fun secondPlayReusesTheMaterialisedRow() = runDbTest {
        newProvider(id = 1)
        val counter = counter()

        val first = counter.recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)!!
        val second = counter.recordPlay(1, "song-1", snapshot, playedAtMs = 2_000, durationMs = 2_000)!!

        assertThat(second).isEqualTo(first)
        assertThat(songDao.selectByProvider(1)).hasSize(1)
        assertThat(songDao.selectById(first)!!.playCount).isEqualTo(2)
        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1")).hasSize(2)
    }

    @Test
    fun skipMarksTheSongWithoutCountingAPlay() = runDbTest {
        newProvider(id = 1)

        val songId = counter().recordSkip(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 500)!!

        val song = songDao.selectById(songId)!!
        assertThat(song.playCount).isEqualTo(0)
        assertThat(song.skipped).isTrue()
        val event = playEventDao.selectByProviderAndExternal(1, "song-1").single()
        assertThat(event.skipped).isTrue()
        assertThat(event.played).isFalse()
    }

    @Test
    fun resumePointMaterialisesTheRowWithoutCountingAPlay() = runDbTest {
        newProvider(id = 1)

        counter().recordResumePoint(1, "song-1", snapshot, positionMs = 42_000, now = 5_000)

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.resumeMs).isEqualTo(42_000)
        assertThat(song.resumeUpdatedAt).isEqualTo(5_000)
        assertThat(song.playCount).isEqualTo(0)
        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1")).isEmpty()
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
    }

    private suspend fun seedAlbumWithTwoTracks(secondPlayCount: Int = 3) {
        newProvider(id = 1)
        newAlbum(id = 10, providerId = 1)
        newSong(id = 11, providerId = 1, albumId = 10)
        newSong(id = 12, providerId = 1, albumId = 10, playCount = secondPlayCount)
    }

    @Test
    fun playRecomputesAlbumAndTrackArtistAggregates() = runDbTest {
        seedAlbumWithTwoTracks()
        newArtist(id = 99, providerId = 1)
        newArtist(id = 98, providerId = 1)
        linkSongArtist(providerId = 1, artistId = 99, songId = 11)
        linkSongArtist(providerId = 1, artistId = 98, songId = 11, role = "feat.")

        counter().recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        val album = requireNotNull(albumDao.selectById(10))
        assertThat(album.playCount).isEqualTo(2)
        assertThat(album.lastPlayed).isEqualTo(1_000)
        assertThat(artistDao.selectById(99)!!.playCount).isEqualTo(1)
        assertThat(artistDao.selectById(99)!!.lastPlayed).isEqualTo(1_000)
        assertThat(artistDao.selectById(98)!!.playCount).isEqualTo(0)
    }

    @Test
    fun albumRuleSumCountsEveryTrackPlay() = runDbTest {
        seedAlbumWithTwoTracks()
        val settings = PlayAggregateSettings().apply {
            update(Options(albumCountRule = AlbumCountRule.SUM))
        }

        counter(settings = settings).recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        assertThat(albumDao.selectById(10)!!.playCount).isEqualTo(4)
    }

    @Test
    fun albumRuleAverageRoundsTheTrackMean() = runDbTest {
        seedAlbumWithTwoTracks()

        counter().recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        assertThat(albumDao.selectById(10)!!.playCount).isEqualTo(2)
    }

    @Test
    fun albumRuleMinimumTakesTheLeastPlayedTrack() = runDbTest {
        seedAlbumWithTwoTracks(secondPlayCount = 5)
        val settings = PlayAggregateSettings().apply {
            update(Options(albumCountRule = AlbumCountRule.MINIMUM))
        }

        counter(settings = settings).recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        assertThat(albumDao.selectById(10)!!.playCount).isEqualTo(1)
    }

    @Test
    fun supportingCreditCountsOnlyWhenTheToggleIsOn() = runDbTest {
        seedAlbumWithTwoTracks()
        newArtist(id = 98, providerId = 1)
        linkSongArtist(providerId = 1, artistId = 98, songId = 11, role = "feat.")
        val settings = PlayAggregateSettings()

        counter(settings = settings).recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        assertThat(artistDao.selectById(98)!!.playCount).isEqualTo(0)

        settings.update(Options(includeSupportingArtists = true))
        counter(settings = settings).recordPlay(1, "song-11", snapshot, playedAtMs = 2_000, durationMs = 2_000)

        assertThat(artistDao.selectById(98)!!.playCount).isEqualTo(2)
    }

    @Test
    fun failedAggregateRecomputeRollsBackTheWholePlay() = runDbTest {
        seedAlbumWithTwoTracks()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_album_recompute BEFORE UPDATE ON albums " +
                "BEGIN SELECT RAISE(ABORT, 'album recompute blocked'); END",
        )

        val failure = runCatching {
            counter().recordPlay(1, "song-11", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        }

        assertThat(failure.isFailure).isTrue()
        assertThat(songDao.selectByProvider(1)).hasSize(2)
        assertThat(songDao.selectByProviderAndExternal(1, "song-11")!!.playCount).isEqualTo(0)
        assertThat(playEventDao.selectByProvider(1)).isEmpty()
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
        assertThat(albumDao.selectById(10)!!.playCount).isEqualTo(0)
        assertThat(albumDao.selectById(10)!!.lastPlayed).isNull()
    }

    @Test
    fun resumeWriteRefreshesTheAlbumWithoutBumpingCounters() = runDbTest {
        newProvider(id = 1)
        newAlbum(id = 10, providerId = 1)
        newSong(id = 11, providerId = 1, albumId = 10, filePath = "/a/one.m4a")
        newSong(id = 12, providerId = 1, albumId = 10, filePath = "/a/two.m4a")
        newArtist(id = 99, providerId = 1)
        linkSongArtist(providerId = 1, artistId = 99, songId = 11)

        counter().recordResumePoint(
            providerId = 1,
            externalId = "song-11",
            snapshot = snapshot.copy(albumExternalId = "alb-10"),
            positionMs = 42_000,
            now = 5_000,
        )

        val song = songDao.selectByProviderAndExternal(1, "song-11")!!
        assertThat(song.resumeMs).isEqualTo(42_000)
        assertThat(song.playCount).isEqualTo(0)
        val album = requireNotNull(albumDao.selectById(10))
        assertThat(album.playCount).isEqualTo(0)
        assertThat(album.lastPlayed).isEqualTo(5_000)
        assertThat(album.resumeTrackPath).isEqualTo("/a/one.m4a")
        assertThat(artistDao.selectById(99)!!.playCount).isEqualTo(0)
        assertThat(playEventDao.selectByProvider(1)).isEmpty()
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun pausedProviderRecordsNothingAndKeepsExistingRows() = runDbTest {
        newProvider(id = 1)
        val gate = HistoryRecordingGate()
        val counter = counter(gate = gate)
        counter().recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        assertThat(playEventDao.selectByProvider(1)).hasSize(1)

        gate.setPaused(1, paused = true)
        assertThat(counter.recordPlay(1, "song-1", snapshot, playedAtMs = 2_000, durationMs = 2_000)).isNull()
        assertThat(counter.recordSkip(1, "song-1", snapshot, playedAtMs = 3_000, durationMs = 2_000)).isNull()
        assertThat(counter.recordResumePoint(1, "song-1", snapshot, positionMs = 5_000, now = 4_000)).isNull()

        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.playCount).isEqualTo(1)
        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.resumeMs).isNull()
        assertThat(playEventDao.selectByProvider(1)).hasSize(1)
        assertThat(outboxDao.selectByProvider(1)).hasSize(1)

        gate.setPaused(1, paused = false)
        assertThat(counter.recordPlay(1, "song-1", snapshot, playedAtMs = 5_000, durationMs = 2_000)).isNotNull()
        assertThat(playEventDao.selectByProvider(1)).hasSize(2)
        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.playCount).isEqualTo(2)
    }

    @Test
    fun localProviderSkipsTheOutboxButKeepsTheLocalPlay() = runDbTest {
        newProvider(id = 1)

        counter(traits = ProviderTraits(reportsUserData = false))
            .recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.playCount).isEqualTo(1)
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
    }
}
