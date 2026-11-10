package app.omnisonic.feature.library.detail

import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.playback.api.queue.PlaybackQueueEntry

/**
 * Catalog rows → queue entries. Songs without a provider external id cannot
 * be resolved by the playback stream resolver, so they are left out and the
 * play-from index re-anchors to the nearest playable row.
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

internal fun PlaylistEntryEntity.toQueueEntry(): PlaybackQueueEntry? {
    val external = externalId ?: return null
    return PlaybackQueueEntry(
        providerId = providerId,
        externalId = external,
        songRowId = songId,
        title = title,
        artistName = displayArtist,
        coverImageId = imagePath,
        durationMs = durationMs,
    )
}
