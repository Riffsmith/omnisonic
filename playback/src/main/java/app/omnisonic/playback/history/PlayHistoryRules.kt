package app.omnisonic.playback.history

/**
 * Eligibility rules for listen-history writes, kept pure so the recorder's
 * event ordering and the rules can be tested apart. A play counts once the
 * position reaches the configured fraction of the duration; a track change
 * below the play threshold is a resume candidate only above a minimum
 * position, otherwise the change is ignored.
 */
object PlayHistoryRules {

    const val DEFAULT_PLAY_THRESHOLD_FRACTION = 0.95

    /** Absolute floor for a resume point; short accidental starts never persist. */
    const val DEFAULT_RESUME_MIN_POSITION_MS = 10_000L

    /** Relative floor so long tracks need a proportionally larger start. */
    const val DEFAULT_RESUME_MIN_FRACTION = 0.05

    fun countsAsPlay(
        positionMs: Long,
        durationMs: Long?,
        thresholdFraction: Double = DEFAULT_PLAY_THRESHOLD_FRACTION,
    ): Boolean {
        if (durationMs == null || durationMs <= 0L) return false
        return positionMs >= (durationMs * thresholdFraction).toLong()
    }

    /**
     * Resume point for an early track change, or null when the change is below
     * the minimum position or the playback already counted as a full play
     * (a completed listen needs no resume marker).
     */
    fun resumePointMs(
        positionMs: Long,
        durationMs: Long?,
        minPositionMs: Long = DEFAULT_RESUME_MIN_POSITION_MS,
        minFraction: Double = DEFAULT_RESUME_MIN_FRACTION,
        playThresholdFraction: Double = DEFAULT_PLAY_THRESHOLD_FRACTION,
    ): Long? {
        if (positionMs <= 0L) return null
        val duration = durationMs?.takeIf { it > 0L }
        val floor = if (duration != null) {
            maxOf(minPositionMs, (duration * minFraction).toLong())
        } else {
            minPositionMs
        }
        if (positionMs < floor) return null
        if (duration != null && countsAsPlay(positionMs, duration, playThresholdFraction)) return null
        return positionMs
    }
}
