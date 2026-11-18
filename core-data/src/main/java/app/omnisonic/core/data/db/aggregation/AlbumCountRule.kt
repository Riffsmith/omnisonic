package app.omnisonic.core.data.db.aggregation

import app.omnisonic.core.data.db.dao.AggregateDao

enum class AlbumCountRule(val label: String) {
    SUM("sum"),
    AVERAGE("average"),
    MINIMUM("minimum"),
}

suspend fun AggregateDao.recomputeAlbums(albumIds: List<Long>, rule: AlbumCountRule) {
    if (albumIds.isEmpty()) return
    when (rule) {
        AlbumCountRule.SUM -> recomputeAlbumsSumById(albumIds)
        AlbumCountRule.AVERAGE -> recomputeAlbumsAverageById(albumIds)
        AlbumCountRule.MINIMUM -> recomputeAlbumsMinimumById(albumIds)
    }
}

suspend fun AggregateDao.recomputeAlbumsForProvider(providerId: Long, rule: AlbumCountRule) {
    when (rule) {
        AlbumCountRule.SUM -> recomputeAlbumsSumByProvider(providerId)
        AlbumCountRule.AVERAGE -> recomputeAlbumsAverageByProvider(providerId)
        AlbumCountRule.MINIMUM -> recomputeAlbumsMinimumByProvider(providerId)
    }
}

/** Count-only album recompute (history controls); keeps `resumeTrackPath` untouched. */
suspend fun AggregateDao.recomputeAlbumCounts(albumIds: List<Long>, rule: AlbumCountRule) {
    if (albumIds.isEmpty()) return
    recomputeAlbumCountsById(albumIds, rule.label)
}

/** Count-only album recompute for a whole provider (history wipe). */
suspend fun AggregateDao.recomputeAlbumCountsForProvider(providerId: Long, rule: AlbumCountRule) {
    recomputeAlbumCountsByProvider(providerId, rule.label)
}

/** Count-only artist recompute (history controls). */
suspend fun AggregateDao.recomputeArtistCounts(artistIds: List<Long>, includeSupporting: Boolean) {
    if (artistIds.isEmpty()) return
    recomputeArtistCountsById(artistIds, includeSupporting)
}

/** Count-only artist recompute for a whole provider (history wipe). */
suspend fun AggregateDao.recomputeArtistCountsForProvider(providerId: Long, includeSupporting: Boolean) {
    recomputeArtistCountsByProvider(providerId, includeSupporting)
}
