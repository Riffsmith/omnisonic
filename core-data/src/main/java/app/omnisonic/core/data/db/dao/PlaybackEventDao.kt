package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import app.omnisonic.core.data.db.entity.PlaybackEventEntity

@Dao
interface PlaybackEventDao {

    @Query(
        """
        INSERT OR IGNORE INTO playback_events(providerId, externalId, date, played, skipped, durationMs)
        VALUES(:providerId, :externalId, :date, 0, 0, 0)
        """,
    )
    suspend fun insertDayEvent(
        providerId: Long,
        externalId: String,
        date: Long,
    )

    @Query(
        """
        UPDATE playback_events SET
          played = played | :played,
          skipped = skipped | :skipped,
          durationMs = durationMs + :durationMs
        WHERE providerId = :providerId AND externalId = :externalId AND date = :date
        """,
    )
    suspend fun mergeDayEvent(
        providerId: Long,
        externalId: String,
        date: Long,
        played: Boolean,
        skipped: Boolean,
        durationMs: Long,
    )

    @Transaction
    suspend fun recordDayEvent(
        providerId: Long,
        externalId: String,
        date: Long,
        played: Boolean,
        skipped: Boolean,
        durationMs: Long,
    ) {
        insertDayEvent(providerId, externalId, date)
        mergeDayEvent(providerId, externalId, date, played, skipped, durationMs)
    }

    @Query("SELECT * FROM playback_events WHERE providerId = :providerId AND externalId = :externalId ORDER BY date")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): List<PlaybackEventEntity>

    @Query("SELECT * FROM playback_events WHERE providerId = :providerId ORDER BY date")
    suspend fun selectByProvider(providerId: Long): List<PlaybackEventEntity>

    @Query("SELECT COUNT(*) FROM playback_events")
    suspend fun count(): Int

    @Query("DELETE FROM playback_events")
    suspend fun deleteAll()
}
