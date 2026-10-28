package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import app.omnisonic.core.data.db.entity.PlaybackEventEntity

@Dao
interface PlaybackEventDao {

    @Query(
        """
        INSERT INTO playback_events(providerId, externalId, date, played, skipped, durationMs)
        VALUES(:providerId, :externalId, :date, :played, :skipped, :durationMs)
        ON CONFLICT(providerId, externalId, date) DO UPDATE SET
          played = playback_events.played | :played,
          skipped = playback_events.skipped | :skipped,
          durationMs = playback_events.durationMs + :durationMs
        """,
    )
    suspend fun recordDayEvent(
        providerId: Long,
        externalId: String,
        date: Long,
        played: Boolean,
        skipped: Boolean,
        durationMs: Long,
    )

    @Query("SELECT * FROM playback_events WHERE providerId = :providerId AND externalId = :externalId ORDER BY date")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): List<PlaybackEventEntity>

    @Query("SELECT * FROM playback_events WHERE providerId = :providerId ORDER BY date")
    suspend fun selectByProvider(providerId: Long): List<PlaybackEventEntity>

    @Query("SELECT COUNT(*) FROM playback_events")
    suspend fun count(): Int

    @Query("DELETE FROM playback_events")
    suspend fun deleteAll()
}
