package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.ArtistEntity

@Dao
interface ArtistDao {

    @Upsert
    suspend fun upsertRow(artist: ArtistEntity): Long

    @Transaction
    suspend fun upsert(artist: ArtistEntity): Long {
        val existingId = artist.id.takeIf { it != 0L }
            ?: artist.externalId?.let { selectIdByProviderAndExternal(artist.providerId, it) }
        return if (existingId == null) {
            upsertRow(artist)
        } else {
            upsertRow(artist.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(artists: List<ArtistEntity>)

    @Transaction
    suspend fun upsertAll(artists: List<ArtistEntity>) {
        artists.forEach { upsert(it) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(artist: ArtistEntity): Long

    @Query("SELECT id FROM artists WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectIdByProviderAndExternal(providerId: Long, externalId: String): Long?

    /** Server-owned columns only; artist biography text and playback state are untouched. */
    @Query(
        """
        UPDATE artists SET
            title = :title,
            latinTitle = :latinTitle,
            sortTitle = :sortTitle,
            imagePath = :imagePath,
            songCount = :songCount,
            albumCount = :albumCount,
            lastSeenSyncId = :lastSeenSyncId,
            updatedAt = :updatedAt
        WHERE providerId = :providerId AND externalId = :externalId
        """,
    )
    suspend fun updateServerFields(
        providerId: Long,
        externalId: String,
        title: String,
        latinTitle: String?,
        sortTitle: String?,
        imagePath: String?,
        songCount: Int,
        albumCount: Int,
        lastSeenSyncId: Long,
        updatedAt: Long,
    )

    @Transaction
    suspend fun upsertServerFields(artist: ArtistEntity, lastSeenSyncId: Long): Long {
        insertOrIgnore(artist.copy(lastSeenSyncId = lastSeenSyncId))
        updateServerFields(
            providerId = artist.providerId,
            externalId = artist.externalId ?: return 0,
            title = artist.title,
            latinTitle = artist.latinTitle,
            sortTitle = artist.sortTitle,
            imagePath = artist.imagePath,
            songCount = artist.songCount,
            albumCount = artist.albumCount,
            lastSeenSyncId = lastSeenSyncId,
            updatedAt = artist.updatedAt,
        )
        return selectIdByProviderAndExternal(artist.providerId, artist.externalId) ?: 0
    }

    @Query("SELECT * FROM artists ORDER BY id")
    suspend fun selectAll(): List<ArtistEntity>

    /** Alphabetical browse page (ProfileScope convention — `ProfileScopeQueryTest`). */
    @Query(
        """
        SELECT * FROM artists
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY COALESCE(sortTitle, title) COLLATE NOCASE, title COLLATE NOCASE, id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun browseArtistsRaw(excludedProviderIdsCsv: String, limit: Int, offset: Int): List<ArtistEntity>

    suspend fun browseArtists(scope: ProfileScope, limit: Int, offset: Int): List<ArtistEntity> =
        browseArtistsRaw(scope.excludedProviderIds.joinToString(","), limit, offset)

    @Query(
        """
        SELECT COUNT(*) FROM artists
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun countArtistsRaw(excludedProviderIdsCsv: String): Int

    suspend fun countArtists(scope: ProfileScope): Int =
        countArtistsRaw(scope.excludedProviderIds.joinToString(","))

    @Query("SELECT * FROM artists WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun selectById(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): ArtistEntity?

    @Query("SELECT MAX(lastSeenSyncId) FROM artists WHERE providerId = :providerId")
    suspend fun selectMaxLastSeenSyncId(providerId: Long): Long?

    /**
     * Artists of this provider not stamped by the current sync run and no
     * longer linked to any song or album. Rows without any stamp are unseen
     * too: the caller (SubsonicLibrarySyncer.prune) may only reach pruning
     * after the FIX-02 safety guards confirmed the walk was complete.
     */
    @Query(
        """
        DELETE FROM artists
        WHERE providerId = :providerId
            AND externalId IS NOT NULL
            AND (lastSeenSyncId IS NULL OR lastSeenSyncId != :runId)
            AND id NOT IN (
                SELECT artistId FROM song_artists WHERE providerId = :providerId
                UNION
                SELECT artistId FROM album_artists WHERE providerId = :providerId
            )
        """,
    )
    suspend fun deleteUnseenWithoutLinks(providerId: Long, runId: Long)

    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}
