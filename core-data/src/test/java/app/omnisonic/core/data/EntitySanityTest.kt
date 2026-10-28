package app.omnisonic.core.data

import android.database.sqlite.SQLiteConstraintException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitySanityTest : DatabaseTestBase() {

    @Test
    fun songRoundTripPreservesAllPersistedFields() = runDbTest {
        newProvider(id = 1)
        val song = newSong(
            id = 2,
            providerId = 1,
            albumId = null,
            title = "Soothsayer",
            playCount = 3,
            lastPlayed = 500,
            resumePointMs = 10,
            filePath = "/music/buckethead.mp3",
        )
        val updated = songDao.selectById(2)?.copy(
            userRating = 4,
            isFavorite = true,
            favoriteAt = 600,
            skipped = true,
            lastSkipped = 700,
            replayGainTrack = 3.2,
            isAudiobook = false,
        ) ?: error("song row missing")
        songDao.upsert(updated)

        val stored = requireNotNull(songDao.selectById(2))
        assertThat(stored.title).isEqualTo("Soothsayer")
        assertThat(stored.playCount).isEqualTo(3)
        assertThat(stored.lastPlayed).isEqualTo(500)
        assertThat(stored.userRating).isEqualTo(4)
        assertThat(stored.isFavorite).isTrue()
        assertThat(stored.skipped).isTrue()
        assertThat(stored.lastSkipped).isEqualTo(700)
        assertThat(stored.replayGainTrack).isEqualTo(3.2)
        assertThat(stored.filePath).isEqualTo(song.filePath)
    }

    @Test
    fun songUniqueIndexRejectsDuplicateProviderExternal() = runDbTest {
        newProvider(id = 1)
        newSong(id = 10, providerId = 1, title = "first")

        val constraint = runCatching {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO songs (id, providerId, externalId, title, playCount, isFavorite, skipped, isAudiobook, updatedAt) VALUES (11, 1, 'song-10', 'second', 0, 0, 0, 0, 1)",
            )
        }.exceptionOrNull()

        assertThat(constraint).isInstanceOf(SQLiteConstraintException::class.java)
    }

    @Test
    fun providerReplaceUpsertKeepsSingleRow() = runDbTest {
        newProvider(id = 1)
        val existing = providerRow(1)
        providerDao.upsert(existing.copy(name = "renamed", updatedAt = 2_000))

        assertThat(providerDao.selectAll()).hasSize(1)
        assertThat(providerRow(1).name).isEqualTo("renamed")
    }

    @Test
    fun outboxUpsertsWithoutExplicitPrimaryKey() = runDbTest {
        newProvider(id = 1)
        seedOutboxItem(providerId = 1)
        seedOutboxItem(providerId = 1)

        assertThat(outboxDao.selectByProvider(1)).hasSize(1)
    }

    @Test
    fun playlistEntryRoundTripKeepsOrder() = runDbTest {
        val provider = newProvider(id = 1)
        val playlist = seedPlaylist(providerId = provider.id)
        seedPlaylistEntry(playlistId = playlist.id, providerId = provider.id)

        val stored = playlistEntryDao.selectByPlaylist(playlist.id)
        assertThat(stored).hasSize(1)
        assertThat(stored.first().entryOrder).isEqualTo(0)
    }

    @Test
    fun savedFilterUniqueNamePerMediaType() = runDbTest {
        seedSavedFilter()
        val constraint = runCatching {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO saved_filters (id, name, mediaType, filterJson, updatedAt) VALUES (9, 'Rock', 'music', '[]', 1)",
            )
        }.exceptionOrNull()

        assertThat(constraint).isInstanceOf(SQLiteConstraintException::class.java)
    }
}
