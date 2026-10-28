package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.InternetRadioEntity

@Dao
interface InternetRadioDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(radio: InternetRadioEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(radios: List<InternetRadioEntity>)

    @Query("SELECT * FROM internet_radios ORDER BY id")
    suspend fun selectAll(): List<InternetRadioEntity>

    @Query("SELECT * FROM internet_radios WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<InternetRadioEntity>

    @Query("DELETE FROM internet_radios WHERE id = :id")
    suspend fun deleteById(id: Long)
}
