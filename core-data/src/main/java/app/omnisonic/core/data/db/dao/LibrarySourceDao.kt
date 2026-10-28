package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.LibrarySourceEntity

@Dao
interface LibrarySourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: LibrarySourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<LibrarySourceEntity>)

    @Query("SELECT * FROM library_sources WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<LibrarySourceEntity>

    @Query("SELECT * FROM library_sources WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): LibrarySourceEntity?

    @Query("DELETE FROM library_sources WHERE id = :id")
    suspend fun deleteById(id: Long)
}
