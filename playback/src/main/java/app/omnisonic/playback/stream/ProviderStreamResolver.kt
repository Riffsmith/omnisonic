package app.omnisonic.playback.stream

import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import app.omnisonic.playback.api.stream.StreamTransport
import app.omnisonic.provider.api.MediaProvider
import javax.inject.Inject

/** Provider seam so tests can stand in for [MediaProvider] access. */
fun interface MediaProviderLookup {
    suspend fun providerFor(providerId: Long): MediaProvider?
}

/**
 * Song-row read seam over the library database; the resolver uses the resume
 * point and [SongReplayGainSource] reads the replay-gain values from it.
 */
fun interface SongRowLookup {
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): SongEntity?
}

/**
 * Production [StreamResolver]: maps a queue entry onto the owning provider's
 * stream URL, seeds the start position from the song-level resume point when
 * the queue snapshot carries none, and flags radio stations as live streams.
 * Returns null (not playable) when the provider is unknown or offers no URL;
 * the queue machine then advances to the next entry.
 */
class ProviderStreamResolver @Inject constructor(
    private val providers: MediaProviderLookup,
    private val songs: SongRowLookup,
) : StreamResolver {

    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? {
        val provider = providers.providerFor(entry.providerId) ?: return null
        val uri = runCatching { provider.streamUrl(entry.externalId) }.getOrNull() ?: return null
        return ResolvedStream(
            entry = entry,
            uri = uri,
            startPositionMs = resumePositionMs(entry),
            requiresNetwork = !isDirect(uri),
            isRadio = entry.isRadioStation,
            transport = if (isDirect(uri)) StreamTransport.DIRECT_URI else StreamTransport.PROVIDER_HTTP,
        )
    }

    private suspend fun resumePositionMs(entry: PlaybackQueueEntry): Long {
        if (entry.isRadioStation) return 0L
        val resume = songs.selectByProviderAndExternal(entry.providerId, entry.externalId)
            ?.resumePointMs
            ?.takeIf { it > 0L }
            ?: return 0L
        // A resume point at or past the end would end the track instantly; restart instead.
        val duration = entry.durationMs
        return if (duration != null && resume >= duration) 0L else resume
    }

    private fun isDirect(uri: String): Boolean =
        uri.startsWith("content://") || uri.startsWith("file://")
}
