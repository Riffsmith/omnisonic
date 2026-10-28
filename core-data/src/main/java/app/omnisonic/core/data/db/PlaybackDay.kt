package app.omnisonic.core.data.db

import java.time.Instant
import java.time.ZoneId

object PlaybackDay {

    /** Epoch millis of local start-of-day keeping the day bucket of [playedAtMs]. */
    fun dayStart(playedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(playedAtMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
