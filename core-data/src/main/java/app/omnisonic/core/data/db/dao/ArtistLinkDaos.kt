package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.AlbumArtistEntity
import app.omnisonic.core.data.db.entity.SongArtistEntity

@Dao
interface AlbumArtistDao {

    @Upsert
    suspend fun upsertRow(link: AlbumArtistEntity): Long

    @Transaction
    suspend fun upsert(link: AlbumArtistEntity): Long {
        val existingId = link.id.takeIf { it != 0L } ?: selectIdByNaturalKey(
            link.providerId, link.albumId, link.artistId, link.role,
        )
        return if (existingId == null) {
            upsertRow(link)
        } else {
            upsertRow(link.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(links: List<AlbumArtistEntity>)

    @Transaction
    suspend fun upsertAll(links: List<AlbumArtistEntity>) {
        links.forEach { upsert(it) }
    }

    @Query(
        """
        SELECT id FROM album_artists
        WHERE providerId = :providerId AND albumId = :albumId AND artistId = :artistId AND role = :role
        """,
    )
    suspend fun selectIdByNaturalKey(providerId: Long, albumId: Long, artistId: Long, role: String): Long?

    @Query("SELECT * FROM album_artists WHERE albumId = :albumId ORDER BY id")
    suspend fun selectByAlbum(albumId: Long): List<AlbumArtistEntity>

    @Query("DELETE FROM album_artists WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: Long)

    @Query("DELETE FROM album_artists WHERE albumId IN (:albumIds)")
    suspend fun deleteByAlbumIds(albumIds: List<Long>)
}

@Dao
interface SongArtistDao {

    @Upsert
    suspend fun upsertRow(link: SongArtistEntity): Long

    @Transaction
    suspend fun upsert(link: SongArtistEntity): Long {
        val existingId = link.id.takeIf { it != 0L } ?: selectIdByNaturalKey(
            link.providerId, link.songId, link.artistId, link.role,
        )
        return if (existingId == null) {
            upsertRow(link)
        } else {
            upsertRow(link.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(links: List<SongArtistEntity>)

    @Transaction
    suspend fun upsertAll(links: List<SongArtistEntity>) {
        links.forEach { upsert(it) }
    }

    @Query(
        """
        SELECT id FROM song_artists
        WHERE providerId = :providerId AND songId = :songId AND artistId = :artistId AND role = :role
        """,
    )
    suspend fun selectIdByNaturalKey(providerId: Long, songId: Long, artistId: Long, role: String): Long?

    @Query("SELECT * FROM song_artists WHERE songId = :songId ORDER BY id")
    suspend fun selectBySong(songId: Long): List<SongArtistEntity>

    @Query("DELETE FROM song_artists WHERE songId = :songId")
    suspend fun deleteBySong(songId: Long)

    @Query("DELETE FROM song_artists WHERE songId IN (:songIds)")
    suspend fun deleteBySongIds(songIds: List<Long>)
}
