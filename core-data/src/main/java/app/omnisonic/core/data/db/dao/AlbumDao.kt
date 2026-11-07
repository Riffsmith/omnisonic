package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.AlbumEntity

@Dao
interface AlbumDao {

    @Upsert
    suspend fun upsertRow(album: AlbumEntity): Long

    @Transaction
    suspend fun upsert(album: AlbumEntity): Long {
        val existingId = album.id.takeIf { it != 0L }
            ?: album.externalId?.let { selectIdByProviderAndExternal(album.providerId, it) }
        return if (existingId == null) {
            upsertRow(album)
        } else {
            upsertRow(album.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(albums: List<AlbumEntity>)

    @Transaction
    suspend fun upsertAll(albums: List<AlbumEntity>) {
        albums.forEach { upsert(it) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(album: AlbumEntity): Long

    @Query("SELECT id FROM albums WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectIdByProviderAndExternal(providerId: Long, externalId: String): Long?

    /** Server-owned columns only; locally-owned editorial and playback state is untouched. */
    @Query(
        """
        UPDATE albums SET
            title = :title,
            latinTitle = :latinTitle,
            sortTitle = :sortTitle,
            displayArtist = :displayArtist,
            year = :year,
            imagePath = :imagePath,
            durationMs = :durationMs,
            songCount = :songCount,
            genresJson = :genresJson,
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
        displayArtist: String?,
        year: Int?,
        imagePath: String?,
        durationMs: Long?,
        songCount: Int,
        genresJson: String?,
        lastSeenSyncId: Long,
        updatedAt: Long,
    )

    @Transaction
    suspend fun upsertServerFields(album: AlbumEntity, lastSeenSyncId: Long): Long {
        insertOrIgnore(album.copy(lastSeenSyncId = lastSeenSyncId))
        updateServerFields(
            providerId = album.providerId,
            externalId = album.externalId ?: return 0,
            title = album.title,
            latinTitle = album.latinTitle,
            sortTitle = album.sortTitle,
            displayArtist = album.displayArtist,
            year = album.year,
            imagePath = album.imagePath,
            durationMs = album.durationMs,
            songCount = album.songCount,
            genresJson = album.genresJson,
            lastSeenSyncId = lastSeenSyncId,
            updatedAt = album.updatedAt,
        )
        return selectIdByProviderAndExternal(album.providerId, album.externalId) ?: 0
    }

    @Query("SELECT * FROM albums ORDER BY id")
    suspend fun selectAll(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun selectById(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): AlbumEntity?

    @Query(
        """
        SELECT * FROM albums
        WHERE providerId = :providerId
        ORDER BY playCount DESC, lastPlayed DESC
        LIMIT :limit
        """,
    )
    suspend fun selectMostPlayed(providerId: Long, limit: Int): List<AlbumEntity>

    @Query("SELECT MAX(lastSeenSyncId) FROM albums WHERE providerId = :providerId")
    suspend fun selectMaxLastSeenSyncId(providerId: Long): Long?

    /**
     * Albums of this provider that were not stamped by the current sync run
     * and have no remaining songs. Rows without any stamp are unseen too: the
     * caller (SubsonicLibrarySyncer.prune) may only reach pruning after the
     * FIX-02 safety guards confirmed the walk was complete.
     */
    @Query(
        """
        SELECT id FROM albums
        WHERE providerId = :providerId
            AND externalId IS NOT NULL
            AND (lastSeenSyncId IS NULL OR lastSeenSyncId != :runId)
            AND id NOT IN (
                SELECT DISTINCT albumId FROM songs
                WHERE providerId = :providerId AND albumId IS NOT NULL
            )
        """,
    )
    suspend fun selectUnseenWithoutSongsIds(providerId: Long, runId: Long): List<Long>

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Long>)

    @Transaction
    suspend fun deleteByIds(ids: List<Long>) {
        ids.forEachSqlChunk { deleteByIdsChunk(it) }
    }

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM albums")
    suspend fun deleteAll()
}
