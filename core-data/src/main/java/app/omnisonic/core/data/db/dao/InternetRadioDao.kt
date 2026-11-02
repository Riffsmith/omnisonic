package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.InternetRadioEntity

@Dao
interface InternetRadioDao {

    @Upsert
    suspend fun upsertRow(radio: InternetRadioEntity): Long

    @Transaction
    suspend fun upsert(radio: InternetRadioEntity): Long {
        val existingId = radio.id.takeIf { it != 0L }
            ?: radio.externalId?.let { selectIdByProviderAndExternal(radio.providerId, it) }
        return if (existingId == null) {
            upsertRow(radio)
        } else {
            upsertRow(radio.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(radios: List<InternetRadioEntity>)

    @Transaction
    suspend fun upsertAll(radios: List<InternetRadioEntity>) {
        radios.forEach { upsert(it) }
    }

    @Query("SELECT id FROM internet_radios WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectIdByProviderAndExternal(providerId: Long, externalId: String): Long?

    @Query("SELECT * FROM internet_radios ORDER BY id")
    suspend fun selectAll(): List<InternetRadioEntity>

    @Query("SELECT * FROM internet_radios WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<InternetRadioEntity>

    @Query("SELECT * FROM internet_radios WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): InternetRadioEntity?

    @Query("DELETE FROM internet_radios WHERE id = :id")
    suspend fun deleteById(id: Long)
}
