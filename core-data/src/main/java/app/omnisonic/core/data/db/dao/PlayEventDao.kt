package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.omnisonic.core.data.db.PlaybackDay
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.RecentPlayRow
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

    /**
     * Recent-plays page (ProfileScope convention — `ProfileScopeQueryTest`):
     * newest first on the absolute stamp, tie-broken by row id so LIMIT/OFFSET
     * windows stay stable. Song/album joins are LEFT so a pruned song row
     * never hides its events.
     */
    @Query(
        """
        SELECT play_events.id AS eventId, play_events.providerId AS providerId,
            play_events.externalId AS externalId, play_events.playedAtMs AS playedAtMs,
            play_events.played AS played, play_events.skipped AS skipped,
            play_events.durationMs AS durationMs,
            songs.id AS songId, songs.title AS songTitle, songs.displayArtist AS displayArtist,
            albums.title AS albumTitle, songs.imagePath AS imagePath
        FROM play_events
        LEFT JOIN songs ON songs.providerId = play_events.providerId
            AND songs.externalId = play_events.externalId
        LEFT JOIN albums ON albums.id = songs.albumId
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || play_events.providerId || ',') = 0
        ORDER BY play_events.playedAtMs DESC, play_events.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun recentPlaysRaw(excludedProviderIdsCsv: String, limit: Int, offset: Int): List<RecentPlayRow>

    suspend fun recentPlays(scope: ProfileScope, limit: Int, offset: Int): List<RecentPlayRow> =
        recentPlaysRaw(scope.excludedProviderIds.joinToString(","), limit, offset)

    @Query(
        """
        SELECT COUNT(*) FROM play_events
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun countRecentPlaysRaw(excludedProviderIdsCsv: String): Int

    suspend fun countRecentPlays(scope: ProfileScope): Int =
        countRecentPlaysRaw(scope.excludedProviderIds.joinToString(","))

    @Query("SELECT COUNT(*) FROM play_events")
    suspend fun count(): Int

    @Query("DELETE FROM play_events")
    suspend fun deleteAll()
}
