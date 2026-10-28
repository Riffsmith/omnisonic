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
