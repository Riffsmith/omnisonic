package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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
            titleRoman = :titleRoman,
            sortTitle = :sortTitle,
            imagePath = :imagePath,
            songCount = :songCount,
            albumCount = :albumCount,
            updatedAt = :updatedAt
        WHERE providerId = :providerId AND externalId = :externalId
        """,
    )
    suspend fun updateServerFields(
        providerId: Long,
        externalId: String,
        title: String,
        titleRoman: String?,
        sortTitle: String?,
        imagePath: String?,
        songCount: Int,
        albumCount: Int,
        updatedAt: Long,
    )

    @Transaction
    suspend fun upsertServerFields(artist: ArtistEntity): Long {
        insertOrIgnore(artist)
        updateServerFields(
            providerId = artist.providerId,
            externalId = artist.externalId ?: return 0,
            title = artist.title,
            titleRoman = artist.titleRoman,
            sortTitle = artist.sortTitle,
            imagePath = artist.imagePath,
            songCount = artist.songCount,
            albumCount = artist.albumCount,
            updatedAt = artist.updatedAt,
        )
        return selectIdByProviderAndExternal(artist.providerId, artist.externalId) ?: 0
    }

    @Query("SELECT * FROM artists ORDER BY id")
    suspend fun selectAll(): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun selectById(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): ArtistEntity?

    /** Artists of this provider not seen in the sync and no longer linked to any song or album. */
    @Query(
        """
        DELETE FROM artists
        WHERE providerId = :providerId
            AND externalId IS NOT NULL
            AND externalId NOT IN (:seenExternalIds)
            AND id NOT IN (
                SELECT artistId FROM song_artists WHERE providerId = :providerId
                UNION
                SELECT artistId FROM album_artists WHERE providerId = :providerId
            )
        """,
    )
    suspend fun deleteUnseenWithoutLinks(providerId: Long, seenExternalIds: List<String>)

    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}
