package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity

/**
 * Read-only catalog snapshots for automatic-rule expansion (task 7b). Every
 * query is provider-scoped SQL with no `IN` list, so a large playlist or album
 * never approaches SQLite's 999-variable floor. Raw+wrapper pairs follow the
 * `ProfileScope` convention; internal expansion passes
 * [ProfileScope.Unrestricted].
 */
@Dao
interface AutomaticRuleCatalogDao {

    @Query(
        """
        SELECT COUNT(*) FROM providers
        WHERE id = :providerId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || id || ',') = 0
        """,
    )
    suspend fun providerExistsRaw(providerId: Long, excludedProviderIdsCsv: String): Int

    suspend fun providerExists(providerId: Long, scope: ProfileScope): Boolean =
        providerExistsRaw(providerId, scope.excludedProviderIds.joinToString(",")) > 0

    @Query(
        """
        SELECT * FROM songs
        WHERE providerId = :providerId
            AND isFavorite = 1
            AND missingOnServer = 0
            AND externalId IS NOT NULL AND externalId != ''
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY id
        """,
    )
    suspend fun favoriteSongsRaw(providerId: Long, excludedProviderIdsCsv: String): List<SongEntity>

    suspend fun favoriteSongs(providerId: Long, scope: ProfileScope): List<SongEntity> =
        favoriteSongsRaw(providerId, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM playlists
        WHERE providerId = :providerId AND externalId = :targetExternalId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        LIMIT 1
        """,
    )
    suspend fun playlistTargetRaw(
        providerId: Long,
        targetExternalId: String,
        excludedProviderIdsCsv: String,
    ): PlaylistEntity?

    suspend fun playlistTarget(
        providerId: Long,
        targetExternalId: String,
        scope: ProfileScope,
    ): PlaylistEntity? =
        playlistTargetRaw(providerId, targetExternalId, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT COUNT(*) FROM playlist_items
        WHERE playlistId = :playlistId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun playlistEntryCountRaw(playlistId: Long, excludedProviderIdsCsv: String): Int

    suspend fun playlistEntryCount(playlistId: Long, scope: ProfileScope): Int =
        playlistEntryCountRaw(playlistId, scope.excludedProviderIds.joinToString(","))

    /** Entries resolve by local row id or, before the entry is re-synced, by stable external id. */
    @Query(
        """
        SELECT songs.* FROM songs
        JOIN playlist_items ON playlist_items.playlistId = :playlistId
            AND playlist_items.providerId = songs.providerId
            AND (
                playlist_items.songId = songs.id
                OR playlist_items.externalId = songs.externalId
            )
        WHERE songs.providerId = :providerId
            AND songs.externalId IS NOT NULL AND songs.externalId != ''
            AND songs.missingOnServer = 0
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || songs.providerId || ',') = 0
        ORDER BY playlist_items.position, playlist_items.id, songs.id
        """,
    )
    suspend fun playlistSongsRaw(
        playlistId: Long,
        providerId: Long,
        excludedProviderIdsCsv: String,
    ): List<SongEntity>

    suspend fun playlistSongs(
        playlistId: Long,
        providerId: Long,
        scope: ProfileScope,
    ): List<SongEntity> =
        playlistSongsRaw(playlistId, providerId, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM albums
        WHERE providerId = :providerId AND externalId = :targetExternalId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        LIMIT 1
        """,
    )
    suspend fun albumTargetRaw(
        providerId: Long,
        targetExternalId: String,
        excludedProviderIdsCsv: String,
    ): AlbumEntity?

    suspend fun albumTarget(
        providerId: Long,
        targetExternalId: String,
        scope: ProfileScope,
    ): AlbumEntity? =
        albumTargetRaw(providerId, targetExternalId, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM songs
        WHERE albumId = :albumId
            AND providerId = :providerId
            AND externalId IS NOT NULL AND externalId != ''
            AND missingOnServer = 0
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY (discNo IS NULL), discNo, (trackNo IS NULL), trackNo,
            COALESCE(sortTitle, title) COLLATE NOCASE, title COLLATE NOCASE, id
        """,
    )
    suspend fun albumSongsRaw(
        albumId: Long,
        providerId: Long,
        excludedProviderIdsCsv: String,
    ): List<SongEntity>

    suspend fun albumSongs(
        albumId: Long,
        providerId: Long,
        scope: ProfileScope,
    ): List<SongEntity> =
        albumSongsRaw(albumId, providerId, scope.excludedProviderIds.joinToString(","))
}
