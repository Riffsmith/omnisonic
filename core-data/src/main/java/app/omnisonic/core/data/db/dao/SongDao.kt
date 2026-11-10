package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.json.JsonText
import app.omnisonic.core.data.search.SearchQuery

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
     * SET list. [lastSeenSyncId] stamps the row as seen by the sync run that
     * wrote it (D-10). [missingOnServer] is sync-owned in both directions:
     * [tombstoneUnseen] sets it for rows the server stopped reporting, and
     * this update clears it because the caller only runs for a song the
     * server just reported again (FIX-06).
     */
    @Query(
        """
        UPDATE songs SET
            albumId = :albumId,
            title = :title,
            latinTitle = :latinTitle,
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
            missingOnServer = 0,
            lastSeenSyncId = :lastSeenSyncId,
            updatedAt = :updatedAt
        WHERE providerId = :providerId AND externalId = :externalId
        """,
    )
    suspend fun updateServerFields(
        providerId: Long,
        externalId: String,
        albumId: Long?,
        title: String,
        latinTitle: String?,
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
        lastSeenSyncId: Long,
        updatedAt: Long,
    )

    @Transaction
    suspend fun upsertServerFields(song: SongEntity, lastSeenSyncId: Long): Long {
        insertOrIgnore(song.copy(lastSeenSyncId = lastSeenSyncId))
        updateServerFields(
            providerId = song.providerId,
            externalId = song.externalId ?: return 0,
            albumId = song.albumId,
            title = song.title,
            latinTitle = song.latinTitle,
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
            lastSeenSyncId = lastSeenSyncId,
            updatedAt = song.updatedAt,
        )
        return selectIdByProviderAndExternal(song.providerId, song.externalId) ?: 0
    }

    @Query("SELECT * FROM songs ORDER BY id")
    suspend fun selectAll(): List<SongEntity>

    /** Alphabetical browse page (ProfileScope convention — `ProfileScopeQueryTest`). */
    @Query(
        """
        SELECT * FROM songs
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY COALESCE(sortTitle, title) COLLATE NOCASE, title COLLATE NOCASE, id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun browseSongsRaw(excludedProviderIdsCsv: String, limit: Int, offset: Int): List<SongEntity>

    suspend fun browseSongs(scope: ProfileScope, limit: Int, offset: Int): List<SongEntity> =
        browseSongsRaw(scope.excludedProviderIds.joinToString(","), limit, offset)

    @Query(
        """
        SELECT COUNT(*) FROM songs
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun countSongsRaw(excludedProviderIdsCsv: String): Int

    suspend fun countSongs(scope: ProfileScope): Int =
        countSongsRaw(scope.excludedProviderIds.joinToString(","))

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY id")
    suspend fun selectByAlbum(albumId: Long): List<SongEntity>

    /**
     * Album detail page (ProfileScope convention): disc/track order with
     * unknown numbers last, then the alphabetical tiebreak.
     */
    @Query(
        """
        SELECT * FROM songs
        WHERE albumId = :albumId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY (discNo IS NULL), discNo, (trackNo IS NULL), trackNo,
            COALESCE(sortTitle, title) COLLATE NOCASE, id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun albumTracksRaw(albumId: Long, excludedProviderIdsCsv: String, limit: Int, offset: Int): List<SongEntity>

    suspend fun albumTracks(scope: ProfileScope, albumId: Long, limit: Int, offset: Int): List<SongEntity> =
        albumTracksRaw(albumId, scope.excludedProviderIds.joinToString(","), limit, offset)

    /**
     * Artist detail page (ProfileScope convention): songs linked through
     * `song_artists`, most played first. DISTINCT collapses multi-role
     * credits into one row.
     */
    @Query(
        """
        SELECT DISTINCT songs.* FROM songs
        JOIN song_artists ON song_artists.songId = songs.id AND song_artists.artistId = :artistId
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || songs.providerId || ',') = 0
        ORDER BY songs.playCount DESC, (songs.lastPlayed IS NULL) ASC, songs.lastPlayed DESC,
            COALESCE(songs.sortTitle, songs.title) COLLATE NOCASE, songs.id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun artistTracksRaw(artistId: Long, excludedProviderIdsCsv: String, limit: Int, offset: Int): List<SongEntity>

    suspend fun artistTracks(scope: ProfileScope, artistId: Long, limit: Int, offset: Int): List<SongEntity> =
        artistTracksRaw(artistId, scope.excludedProviderIds.joinToString(","), limit, offset)

    /**
     * Genre detail page (ProfileScope convention): [genreJsonPattern] is the
     * quote-delimited JSON literal of the genre name (built by the wrapper)
     * matched against the `genresJson` list column, so "Jazz" never matches
     * "Acid Jazz" or "Jazz Fusion".
     */
    @Query(
        """
        SELECT * FROM songs
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
            AND genresJson LIKE :genreJsonPattern ESCAPE '\'
        ORDER BY COALESCE(sortTitle, title) COLLATE NOCASE, title COLLATE NOCASE, id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun genreTracksRaw(genreJsonPattern: String, excludedProviderIdsCsv: String, limit: Int, offset: Int): List<SongEntity>

    suspend fun genreTracks(scope: ProfileScope, genreTitle: String, limit: Int, offset: Int): List<SongEntity> {
        val pattern = "%${SearchQuery.escape(JsonText.encodeString(genreTitle))}%"
        return genreTracksRaw(pattern, scope.excludedProviderIds.joinToString(","), limit, offset)
    }

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
        UPDATE songs SET resumeMs = :resumeMs, lastPlayed = :now, resumeUpdatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun setResumePoint(id: Long, resumeMs: Long, now: Long)

    @Query(
        """
        UPDATE songs SET resumeMs = :resumeMs, resumeUpdatedAt = :serverUpdatedAtMs
        WHERE id = :id
        """,
    )
    suspend fun applyServerResumePoint(id: Long, resumeMs: Long?, serverUpdatedAtMs: Long)

    @Query("UPDATE songs SET userRating = :rating WHERE id = :id")
    suspend fun setUserRating(id: Long, rating: Int?)

    @Query("UPDATE songs SET isFavorite = :favorite, favoriteAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Query("UPDATE songs SET skipped = 1, lastSkipped = :now WHERE id = :id")
    suspend fun markSkipped(id: Long, now: Long)

    @Query("SELECT MAX(lastSeenSyncId) FROM songs WHERE providerId = :providerId")
    suspend fun selectMaxLastSeenSyncId(providerId: Long): Long?

    @Query("SELECT COUNT(*) FROM songs WHERE providerId = :providerId")
    suspend fun selectCountByProvider(providerId: Long): Int

    /**
     * Rows the current run did not stamp are unseen; rows without any stamp
     * count too, because the caller (SubsonicLibrarySyncer.prune) may only
     * reach pruning after the FIX-02 safety guards confirmed the walk was
     * complete: non-empty seen-set, seen songs at least half the known count,
     * no failed album fetch. D-9: rated, resumed or clipped songs carry local
     * value and are preserved through tombstoning; favorite status alone does
     * not block deletion.
     */
    @Query(
        """
        UPDATE songs SET missingOnServer = 1
        WHERE providerId = :providerId
            AND missingOnServer = 0
            AND externalId IS NOT NULL
            AND (lastSeenSyncId IS NULL OR lastSeenSyncId != :runId)
            AND (
                playCount > 0
                OR userRating IS NOT NULL
                OR resumeMs IS NOT NULL
                OR windowStartMs IS NOT NULL
                OR windowEndMs IS NOT NULL
                OR externalId IN (
                    SELECT externalId FROM outbox_items WHERE providerId = :providerId
                )
            )
        """,
    )
    suspend fun tombstoneUnseen(providerId: Long, runId: Long)

    /**
     * Deletable = unseen with none of the tombstone protections. The D-9
     * columns are excluded here directly, so rated, resumed or clipped songs
     * can never be deleted even if the tombstone pass missed them; the stamp
     * predicate may only run behind the FIX-02 safety guards of
     * SubsonicLibrarySyncer.prune.
     */
    @Query(
        """
        SELECT id FROM songs
        WHERE providerId = :providerId
            AND missingOnServer = 0
            AND playCount = 0
            AND userRating IS NULL
            AND resumeMs IS NULL
            AND windowStartMs IS NULL
            AND windowEndMs IS NULL
            AND (lastSeenSyncId IS NULL OR lastSeenSyncId != :runId)
            AND externalId NOT IN (
                SELECT externalId FROM outbox_items WHERE providerId = :providerId
            )
        """,
    )
    suspend fun selectUnseenDeletableIds(providerId: Long, runId: Long): List<Long>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Long>)

    @Transaction
    suspend fun deleteByIds(ids: List<Long>) {
        ids.forEachSqlChunk { deleteByIdsChunk(it) }
    }

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
}
