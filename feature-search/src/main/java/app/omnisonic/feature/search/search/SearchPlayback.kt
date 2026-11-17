package app.omnisonic.feature.search.search

import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.search.RecentPick
import app.omnisonic.playback.api.queue.PlaybackQueueEntry

/**
 * Catalog rows → queue entries. Songs without a provider external id cannot be
 * resolved by the playback stream resolver, so they are left out and the
 * play-from index re-anchors to the nearest playable row.
 *
 * Duplicated from `:feature-library`'s internal mapper: `:feature-search`
 * cannot see that module's internal declaration, and consolidating the factory
 * into `:playback-api` is a follow-up.
 */
internal fun SongEntity.toQueueEntry(): PlaybackQueueEntry? {
    val external = externalId ?: return null
    return PlaybackQueueEntry(
        providerId = providerId,
        externalId = external,
        songRowId = id,
        title = title,
        artistName = displayArtist,
        coverImageId = imagePath,
        durationMs = durationMs,
    )
}

internal fun RecentPick.toQueueEntry(): PlaybackQueueEntry? {
    val external = externalId ?: return null
    return PlaybackQueueEntry(
        providerId = providerId,
        externalId = external,
        songRowId = id,
        title = title,
        artistName = subtitle,
        coverImageId = imagePath,
        durationMs = durationMs,
    )
}
