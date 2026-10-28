package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.ProviderEntity

@Dao
interface ProviderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity): Long

    @Query("SELECT * FROM providers ORDER BY displayOrder, id")
    suspend fun selectAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun selectById(id: Long): ProviderEntity?

    @Query("SELECT * FROM providers WHERE api = :api")
    suspend fun selectByApi(api: String): List<ProviderEntity>

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE providers SET lastWorkingBlock = :block WHERE id = :id")
    suspend fun setLastWorkingBlock(id: Long, block: Int?)

    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int
}
