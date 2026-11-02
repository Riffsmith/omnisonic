package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.omnisonic.core.data.db.PlaybackDay
import app.omnisonic.core.data.db.entity.PlayEventEntity
import app.omnisonic.core.data.db.entity.PlaybackDayRollup

@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEventEntity): Long

    suspend fun recordPlay(
        providerId: Long,
        externalId: String,
        playedAtMs: Long,
        durationMs: Long,
    ): Long = insertEvent(providerId, externalId, playedAtMs, played = true, skipped = false, durationMs = durationMs)

    suspend fun recordSkip(
        providerId: Long,
        externalId: String,
        playedAtMs: Long,
        durationMs: Long,
    ): Long = insertEvent(providerId, externalId, playedAtMs, played = false, skipped = true, durationMs = durationMs)

    private suspend fun insertEvent(
        providerId: Long,
        externalId: String,
        playedAtMs: Long,
        played: Boolean,
        skipped: Boolean,
        durationMs: Long,
    ): Long = insert(
        PlayEventEntity(
            providerId = providerId,
            externalId = externalId,
            playedAtMs = playedAtMs,
            dayStartMs = PlaybackDay.dayStart(playedAtMs),
            played = played,
            skipped = skipped,
            durationMs = durationMs,
        ),
    )

    @Query("SELECT * FROM play_events WHERE providerId = :providerId AND externalId = :externalId ORDER BY playedAtMs")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): List<PlayEventEntity>

    @Query("SELECT * FROM play_events WHERE providerId = :providerId ORDER BY playedAtMs")
    suspend fun selectByProvider(providerId: Long): List<PlayEventEntity>

    @Query("SELECT * FROM play_events_daily WHERE providerId = :providerId ORDER BY date, externalId")
    suspend fun selectDailyByProvider(providerId: Long): List<PlaybackDayRollup>

    @Query("SELECT * FROM play_events_daily WHERE providerId = :providerId AND externalId = :externalId ORDER BY date")
    suspend fun selectDailyByProviderAndExternal(providerId: Long, externalId: String): List<PlaybackDayRollup>

    @Query("SELECT COUNT(*) FROM play_events")
    suspend fun count(): Int

    @Query("DELETE FROM play_events")
    suspend fun deleteAll()
}
