package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {

    @Upsert
    suspend fun upsert(provider: ProviderEntity): Long

    @Query("SELECT * FROM providers ORDER BY displayOrder, id")
    suspend fun selectAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun selectById(id: Long): ProviderEntity?

    @Query("SELECT * FROM providers WHERE api = :api")
    suspend fun selectByApi(api: String): List<ProviderEntity>

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Persists the learned failover block and nothing else: `updatedAt` stays
     * untouched because [app.omnisonic.core.data.providers.ProviderClientRegistry]
     * keys its cache on it, and a preference change must not rebuild the
     * provider or its clients.
     */
    @Query("UPDATE providers SET lastWorkingBlock = :block WHERE id = :id")
    suspend fun setLastWorkingBlock(id: Long, block: Int?)

    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int

    /** Live provider count; re-emits on any `providers` write (shell empty-state gate). */
    @Query("SELECT COUNT(*) FROM providers")
    fun countFlow(): Flow<Int>
}
