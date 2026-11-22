package app.omnisonic.playback.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import app.omnisonic.playback.api.stream.StreamTransport
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
 * snapshot remains the only owner of resume positions; a stored file still
 * reports a requested [startPositionMs] back, because the file holds the whole
 * song and the player seeks inside it. Radio entries always stay live. An
 * offline lookup failure falls back to the provider instead of failing
 * playback.
 */
class OfflineFirstStreamResolver(
    private val online: StreamResolver,
    private val offline: OfflineStreamLookup,
    private val desiredVariants: DesiredStreamVariants,
) : StreamResolver {

    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? =
        resolve(entry, 0L)

    override suspend fun resolve(entry: PlaybackQueueEntry, startPositionMs: Long): ResolvedStream? {
        if (!entry.isRadioStation) {
            val chosen = choose(offlineCandidates(entry), desiredKey(entry))
            if (chosen != null) {
                return ResolvedStream(
                    entry = entry,
                    uri = chosen.uri,
                    startPositionMs = startPositionMs.coerceAtLeast(0L),
                    requiresNetwork = false,
                    isRadio = false,
                    cacheKey = chosen.sourceKey,
                    transport = StreamTransport.DIRECT_URI,
                )
            }
        }
        return online.resolve(entry, startPositionMs)
    }

    /**
     * The exact desired stream variant wins (D-56); a null desired key means
     * the settings do not determine one variant, so the deterministic newest
     * readable fallback applies instead of guessing.
     */
    private fun choose(
        candidates: List<OfflineStreamCandidate>,
        desired: String?,
    ): OfflineStreamCandidate? {
        val exact = desired?.let { key -> candidates.firstOrNull { it.sourceKey == key } }
        return exact ?: candidates.maxWithOrNull(
            compareBy<OfflineStreamCandidate> { it.downloadedAtMs ?: Long.MIN_VALUE }
                .thenBy { it.id },
        )
    }

    private suspend fun desiredKey(entry: PlaybackQueueEntry): String? = try {
        desiredVariants.desiredKey(entry)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun offlineCandidates(entry: PlaybackQueueEntry): List<OfflineStreamCandidate> = try {
        offline.candidates(entry.providerId, entry.externalId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}
