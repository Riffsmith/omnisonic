package app.omnisonic.core.data

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.SongEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentPlaysQueryTest : DatabaseTestBase() {

    @Test
    fun `recent plays come newest first with id tie-break and keep played and skipped flags`() = runDbTest {
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 10)
        playEventDao.recordPlay(1, "song-2", playedAtMs = 3_000, durationMs = 10)
        playEventDao.recordSkip(1, "song-3", playedAtMs = 2_000, durationMs = 10)
        playEventDao.recordPlay(1, "song-4", playedAtMs = 3_000, durationMs = 10)

        val rows = playEventDao.recentPlays(ProfileScope.Unrestricted, limit = 10, offset = 0)

        assertThat(rows.map { it.externalId }).containsExactly("song-4", "song-2", "song-3", "song-1").inOrder()
        assertThat(rows.map { it.playedAtMs }).containsExactly(3_000L, 3_000L, 2_000L, 1_000L).inOrder()
        assertThat(rows[0].eventId).isGreaterThan(rows[1].eventId)
        assertThat(rows[2].played).isFalse()
        assertThat(rows[2].skipped).isTrue()
        assertThat(rows[0].skipped).isFalse()
    }

    @Test
    fun `paging windows by offset and limit over the newest-first order`() = runDbTest {
        repeat(5) { n ->
            playEventDao.recordPlay(1, "song-$n", playedAtMs = (n + 1) * 1_000L, durationMs = 10)
        }

        val page1 = playEventDao.recentPlays(ProfileScope.Unrestricted, limit = 2, offset = 0)
        val page2 = playEventDao.recentPlays(ProfileScope.Unrestricted, limit = 2, offset = 2)
        val page3 = playEventDao.recentPlays(ProfileScope.Unrestricted, limit = 2, offset = 4)

        assertThat(page1.map { it.externalId }).containsExactly("song-4", "song-3").inOrder()
        assertThat(page2.map { it.externalId }).containsExactly("song-2", "song-1").inOrder()
        assertThat(page3.map { it.externalId }).containsExactly("song-0")
    }

    @Test
    fun `profile scope hides excluded providers from both the page and the count`() = runDbTest {
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 10)
        playEventDao.recordPlay(2, "song-2", playedAtMs = 2_000, durationMs = 10)
        playEventDao.recordPlay(2, "song-3", playedAtMs = 3_000, durationMs = 10)
        val scope = ProfileScope(excludedProviderIds = listOf(2))

        assertThat(playEventDao.recentPlays(scope, limit = 10, offset = 0).map { it.externalId })
            .containsExactly("song-1")
        assertThat(playEventDao.countRecentPlays(scope)).isEqualTo(1)
        assertThat(playEventDao.countRecentPlays(ProfileScope.Unrestricted)).isEqualTo(3)
    }

    @Test
    fun `rows join the song album and artist display columns`() = runDbTest {
        val album = newAlbum(10, 1, title = "Album A")
        songDao.upsert(
            SongEntity(
                id = 100,
                providerId = 1,
                externalId = "song-100",
                albumId = album.id,
                title = "Song A",
                displayArtist = "Artist A",
                imagePath = "img-1",
                durationMs = 60_000,
                updatedAt = 1_000,
            ),
        )
        playEventDao.recordPlay(1, "song-100", playedAtMs = 5_000, durationMs = 60_000)

        val row = playEventDao.recentPlays(ProfileScope.Unrestricted, limit = 10, offset = 0).single()

        assertThat(row.songId).isEqualTo(100L)
        assertThat(row.songTitle).isEqualTo("Song A")
        assertThat(row.displayArtist).isEqualTo("Artist A")
        assertThat(row.albumTitle).isEqualTo("Album A")
        assertThat(row.imagePath).isEqualTo("img-1")
        assertThat(row.played).isTrue()
        assertThat(row.durationMs).isEqualTo(60_000L)
    }

    @Test
    fun `an event without a materialised song row still lists with the external id`() = runDbTest {
        playEventDao.recordPlay(1, "gone-1", playedAtMs = 5_000, durationMs = 1_000)

        val row = playEventDao.recentPlays(ProfileScope.Unrestricted, limit = 10, offset = 0).single()

        assertThat(row.songId).isNull()
        assertThat(row.songTitle).isNull()
        assertThat(row.albumTitle).isNull()
        assertThat(row.externalId).isEqualTo("gone-1")
    }
}
