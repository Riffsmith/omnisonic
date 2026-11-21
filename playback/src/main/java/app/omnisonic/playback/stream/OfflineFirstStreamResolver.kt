package app.omnisonic.playback.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamKey
import app.omnisonic.playback.api.stream.StreamResolver
import app.omnisonic.playback.api.stream.StreamTransport
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** One readable offline file offered by the core-data playback read seam. */
data class OfflineStreamCandidate(
    val id: Long,
    val sourceKey: String,
    val uri: String,
    val downloadedAtMs: Long?,
)

/** Seam over stored offline files so resolver tests need no database or filesystem. */
fun interface OfflineStreamLookup {
    suspend fun candidates(providerId: Long, externalId: String): List<OfflineStreamCandidate>
}

/**
 * Offline-first [StreamResolver] (Phase 7 task 5c): a readable stored file is
 * preferred over the provider, then the provider URL, then null. The offline
 * source is a direct `file://` URI with no implicit start offset, so the queue
 * snapshot remains the only owner of resume positions. Radio entries always
 * stay live. An offline lookup failure falls back to the provider instead of
 * failing playback.
 */
class OfflineFirstStreamResolver @Inject constructor(
    private val online: ProviderStreamResolver,
    private val offline: OfflineStreamLookup,
) : StreamResolver {

    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? {
        if (!entry.isRadioStation) {
            val chosen = choose(entry, offlineCandidates(entry))
            if (chosen != null) {
                return ResolvedStream(
                    entry = entry,
                    uri = chosen.uri,
                    startPositionMs = 0L,
                    requiresNetwork = false,
                    isRadio = false,
                    cacheKey = chosen.sourceKey,
                    transport = StreamTransport.DIRECT_URI,
                )
            }
        }
        return online.resolve(entry)
    }

    /**
     * The exact desired stream variant wins. Until Phase 8 supplies transcode
     * bitrate/format, the desired key is the auto variant, so an explicit
     * variant downloaded earlier is reachable through the deterministic
     * newest readable fallback rather than by inventing a selection rule.
     */
    private fun choose(
        entry: PlaybackQueueEntry,
        candidates: List<OfflineStreamCandidate>,
    ): OfflineStreamCandidate? {
        val desired = StreamKey(entry.providerId, entry.externalId).cacheKey
        return candidates.firstOrNull { it.sourceKey == desired }
            ?: candidates.maxWithOrNull(
                compareBy<OfflineStreamCandidate> { it.downloadedAtMs ?: Long.MIN_VALUE }
                    .thenBy { it.id },
            )
    }

    private suspend fun offlineCandidates(entry: PlaybackQueueEntry): List<OfflineStreamCandidate> = try {
        offline.candidates(entry.providerId, entry.externalId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}
