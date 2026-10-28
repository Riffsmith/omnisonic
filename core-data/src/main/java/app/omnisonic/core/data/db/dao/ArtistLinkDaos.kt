package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.AlbumArtistEntity
import app.omnisonic.core.data.db.entity.SongArtistEntity

@Dao
interface AlbumArtistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: AlbumArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<AlbumArtistEntity>)

    @Query("SELECT * FROM album_artists WHERE albumId = :albumId ORDER BY id")
    suspend fun selectByAlbum(albumId: Long): List<AlbumArtistEntity>

    @Query("DELETE FROM album_artists WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: Long)
}

@Dao
interface SongArtistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: SongArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<SongArtistEntity>)

    @Query("SELECT * FROM song_artists WHERE songId = :songId ORDER BY id")
    suspend fun selectBySong(songId: Long): List<SongArtistEntity>

    @Query("DELETE FROM song_artists WHERE songId = :songId")
    suspend fun deleteBySong(songId: Long)
}
