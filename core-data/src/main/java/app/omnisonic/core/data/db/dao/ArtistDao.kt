package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.ArtistEntity

@Dao
interface ArtistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists ORDER BY id")
    suspend fun selectAll(): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun selectById(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): ArtistEntity?

    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}
