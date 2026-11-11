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
 * Song-row read seam over the library database, used by
 * [SongReplayGainSource] for replay-gain values. The stream resolver must not
 * read song state through it: a stored resume point is data about the server's
 * copy of a song, not an implicit start offset.
 */
fun interface SongRowLookup {
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): SongEntity?
}

/**
 * Production [StreamResolver]: maps a queue entry onto the owning provider's
 * stream URL and flags radio stations as live streams. A resolved stream
 * always starts at 0 — the queue owns snapshot restore, and a song-level
 * resume point is never applied implicitly. Returns null (not playable) when
 * the provider is unknown or offers no URL; the queue machine then advances to
 * the next entry.
 */
class ProviderStreamResolver @Inject constructor(
    private val providers: MediaProviderLookup,
) : StreamResolver {

    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? {
        val provider = providers.providerFor(entry.providerId) ?: return null
        val uri = runCatching { provider.streamUrl(entry.externalId) }.getOrNull() ?: return null
        return ResolvedStream(
            entry = entry,
            uri = uri,
            requiresNetwork = !isDirect(uri),
            isRadio = entry.isRadioStation,
            transport = if (isDirect(uri)) StreamTransport.DIRECT_URI else StreamTransport.PROVIDER_HTTP,
        )
    }

    private fun isDirect(uri: String): Boolean =
        uri.startsWith("content://") || uri.startsWith("file://")
}
