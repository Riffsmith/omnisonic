package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.LibrarySourceEntity

@Dao
interface LibrarySourceDao {

    @Upsert
    suspend fun upsertRow(source: LibrarySourceEntity): Long

    @Transaction
    suspend fun upsert(source: LibrarySourceEntity): Long {
        val existingId = source.id.takeIf { it != 0L }
            ?: source.externalId?.let { selectIdByProviderAndExternal(source.providerId, it) }
        return if (existingId == null) {
            upsertRow(source)
        } else {
            upsertRow(source.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(sources: List<LibrarySourceEntity>)

    @Transaction
    suspend fun upsertAll(sources: List<LibrarySourceEntity>) {
        sources.forEach { upsert(it) }
    }

    @Query("SELECT id FROM library_sources WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectIdByProviderAndExternal(providerId: Long, externalId: String): Long?

    @Query("SELECT * FROM library_sources WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<LibrarySourceEntity>

    @Query("SELECT * FROM library_sources WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): LibrarySourceEntity?

    @Query("DELETE FROM library_sources WHERE id = :id")
    suspend fun deleteById(id: Long)
}
