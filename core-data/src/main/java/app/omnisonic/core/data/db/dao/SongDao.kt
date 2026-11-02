package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.SongEntity

@Dao
interface SongDao {

    @Upsert
    suspend fun upsertRow(song: SongEntity): Long

    @Transaction
    suspend fun upsert(song: SongEntity): Long {
        val existingId = song.id.takeIf { it != 0L }
            ?: song.externalId?.let { selectIdByProviderAndExternal(song.providerId, it) }
        return if (existingId == null) {
            upsertRow(song)
        } else {
            upsertRow(song.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(songs: List<SongEntity>)

    @Transaction
    suspend fun upsertAll(songs: List<SongEntity>) {
        songs.forEach { upsert(it) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(song: SongEntity): Long

    /**
     * Queue-snapshot materialisation: fills only columns that are still NULL
     * so a snapshot never downgrades fully-synced metadata; locally-owned
     * playback state is not in the SET list at all.
     */
    @Query(
        """
        UPDATE songs SET
            albumId = COALESCE(albumId, :albumId),
            title = COALESCE(title, :title),
            displayArtist = COALESCE(displayArtist, :displayArtist),
            imagePath = COALESCE(imagePath, :imagePath),
            durationMs = COALESCE(durationMs, :durationMs)
        WHERE providerId = :providerId AND externalId = :externalId
        """,
    )
    suspend fun fillSnapshotFields(
        providerId: Long,
        externalId: String,
        albumId: Long?,
        title: String,
        displayArtist: String?,
        imagePath: String?,
        durationMs: Long?,
    )

    @Query("SELECT id FROM songs WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectIdByProviderAndExternal(providerId: Long, externalId: String): Long?

    /**
     * Server-owned columns only; locally-owned playback state (play counts,
     * favorites, ratings, resume points, replay gain, clips) is never in the
     * SET list.
     */
    @Query(
        """
        UPDATE songs SET
            albumId = :albumId,
            title = :title,
            titleRoman = :titleRoman,
            sortTitle = :sortTitle,
            displayArtist = :displayArtist,
            filePath = :filePath,
            durationMs = :durationMs,
            trackNo = :trackNo,
            discNo = :discNo,
            year = :year,
            genresJson = :genresJson,
            imagePath = :imagePath,
            codec = :codec,
            bitrateKbps = :bitrateKbps,
            updatedAt = :updatedAt
        WHERE providerId = :providerId AND externalId = :externalId
        """,
    )
    suspend fun updateServerFields(
        providerId: Long,
        externalId: String,
        albumId: Long?,
        title: String,
        titleRoman: String?,
        sortTitle: String?,
        displayArtist: String?,
        filePath: String?,
        durationMs: Long?,
        trackNo: Int?,
        discNo: Int?,
        year: Int?,
        genresJson: String?,
        imagePath: String?,
        codec: String?,
        bitrateKbps: Int?,
        updatedAt: Long,
    )

    @Transaction
    suspend fun upsertServerFields(song: SongEntity): Long {
        insertOrIgnore(song)
        updateServerFields(
            providerId = song.providerId,
            externalId = song.externalId ?: return 0,
            albumId = song.albumId,
            title = song.title,
            titleRoman = song.titleRoman,
            sortTitle = song.sortTitle,
            displayArtist = song.displayArtist,
            filePath = song.filePath,
            durationMs = song.durationMs,
            trackNo = song.trackNo,
            discNo = song.discNo,
            year = song.year,
            genresJson = song.genresJson,
            imagePath = song.imagePath,
            codec = song.codec,
            bitrateKbps = song.bitrateKbps,
            updatedAt = song.updatedAt,
        )
        return selectIdByProviderAndExternal(song.providerId, song.externalId) ?: 0
    }

    @Query("SELECT * FROM songs ORDER BY id")
    suspend fun selectAll(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY id")
    suspend fun selectByAlbum(albumId: Long): List<SongEntity>

    @Query("SELECT * FROM songs WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun selectById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE providerId = :providerId AND filePath = :filePath")
    suspend fun selectByProviderAndFilePath(providerId: Long, filePath: String): SongEntity?

    @Query("UPDATE songs SET playCount = playCount + :delta, lastPlayed = :now WHERE id = :id")
    suspend fun addPlayCount(id: Long, delta: Int, now: Long)

    @Query(
        """
        UPDATE songs SET resumePointMs = :resumePointMs, lastPlayed = :now, resumeUpdatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun setResumePoint(id: Long, resumePointMs: Long, now: Long)

    @Query(
        """
        UPDATE songs SET resumePointMs = :resumePointMs, resumeUpdatedAt = :serverUpdatedAtMs
        WHERE id = :id
        """,
    )
    suspend fun applyServerResumePoint(id: Long, resumePointMs: Long?, serverUpdatedAtMs: Long)

    @Query("UPDATE songs SET userRating = :rating WHERE id = :id")
    suspend fun setUserRating(id: Long, rating: Int?)

    @Query("UPDATE songs SET isFavorite = :favorite, favoriteAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Query("UPDATE songs SET skipped = 1, lastSkipped = :now WHERE id = :id")
    suspend fun markSkipped(id: Long, now: Long)

    @Query(
        """
        UPDATE songs SET missingOnServer = 1
        WHERE providerId = :providerId
            AND missingOnServer = 0
            AND externalId IS NOT NULL
            AND externalId NOT IN (:seenExternalIds)
            AND (
                playCount > 0
                OR externalId IN (
                    SELECT externalId FROM outbox_items WHERE providerId = :providerId
                )
            )
        """,
    )
    suspend fun tombstoneUnseen(providerId: Long, seenExternalIds: List<String>)

    @Query(
        """
        SELECT id FROM songs
        WHERE providerId = :providerId
            AND externalId IS NOT NULL
            AND missingOnServer = 0
            AND playCount = 0
            AND externalId NOT IN (:seenExternalIds)
            AND externalId NOT IN (
                SELECT externalId FROM outbox_items WHERE providerId = :providerId
            )
        """,
    )
    suspend fun selectUnseenDeletableIds(providerId: Long, seenExternalIds: List<String>): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
}
