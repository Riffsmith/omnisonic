package app.omnisonic.core.data.db

/**
 * One recent-plays list row: the immutable play-event columns joined to the
 * materialised song/album display columns. The song join is a LEFT JOIN — a
 * pruned song row must not hide its events, so the list falls back to
 * [externalId] for the title.
 */
data class RecentPlayRow(
    val eventId: Long,
    val providerId: Long,
    val externalId: String,
    val playedAtMs: Long,
    val played: Boolean,
    val skipped: Boolean,
    val durationMs: Long,
    val songId: Long?,
    val songTitle: String?,
    val displayArtist: String?,
    val albumTitle: String?,
    val imagePath: String?,
    /** Song play count when the row exists, 0 when it was pruned; edit-dialog prefill. */
    val playCount: Int = 0,
)
