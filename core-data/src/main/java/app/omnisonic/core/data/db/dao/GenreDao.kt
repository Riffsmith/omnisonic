package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.GenreEntity

@Dao
interface GenreDao {

    @Upsert
    suspend fun upsertRow(genre: GenreEntity): Long

    @Transaction
    suspend fun upsert(genre: GenreEntity): Long {
        val existingId = genre.id.takeIf { it != 0L }
            ?: genre.externalId?.let { selectIdByProviderAndExternal(genre.providerId, it) }
        return if (existingId == null) {
            upsertRow(genre)
        } else {
            upsertRow(genre.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(genres: List<GenreEntity>)

    @Transaction
    suspend fun upsertAll(genres: List<GenreEntity>) {
        genres.forEach { upsert(it) }
    }

    @Query("SELECT id FROM genres WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectIdByProviderAndExternal(providerId: Long, externalId: String): Long?

    @Query("SELECT * FROM genres ORDER BY id")
    suspend fun selectAll(): List<GenreEntity>

    /** Alphabetical browse page (ProfileScope convention — `ProfileScopeQueryTest`). */
    @Query(
        """
        SELECT * FROM genres
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY COALESCE(sortTitle, title) COLLATE NOCASE, id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun browseGenresRaw(excludedProviderIdsCsv: String, limit: Int, offset: Int): List<GenreEntity>

    suspend fun browseGenres(scope: ProfileScope, limit: Int, offset: Int): List<GenreEntity> =
        browseGenresRaw(scope.excludedProviderIds.joinToString(","), limit, offset)

    @Query(
        """
        SELECT COUNT(*) FROM genres
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun countGenresRaw(excludedProviderIdsCsv: String): Int

    suspend fun countGenres(scope: ProfileScope): Int =
        countGenresRaw(scope.excludedProviderIds.joinToString(","))

    @Query("SELECT * FROM genres WHERE providerId = :providerId ORDER BY title")
    suspend fun selectByProvider(providerId: Long): List<GenreEntity>

    @Query("SELECT * FROM genres WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): GenreEntity?

    @Query("SELECT * FROM genres WHERE id = :id")
    suspend fun selectById(id: Long): GenreEntity?

    @Query("DELETE FROM genres WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM genres")
    suspend fun deleteAll()
}
