package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.AlbumEntity

@Dao
interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

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

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM albums")
    suspend fun deleteAll()
}
