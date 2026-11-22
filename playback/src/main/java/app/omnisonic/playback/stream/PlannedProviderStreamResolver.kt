package app.omnisonic.playback.stream

import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamKey
import app.omnisonic.playback.api.stream.StreamResolver
import app.omnisonic.playback.api.stream.StreamTransport
import app.omnisonic.playback.transcode.RendererCapabilities
import app.omnisonic.playback.transcode.TranscodeDecision
import app.omnisonic.playback.transcode.TranscodePlanInput
import app.omnisonic.playback.transcode.TranscodePlanner
import app.omnisonic.playback.transcode.TranscodeSlotResolver
import app.omnisonic.playback.transcode.TranscodeSource
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderTraits
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Runtime traits per persisted provider, so planning never assumes transcode support. */
fun interface ProviderTraitsLookup {
    suspend fun traitsFor(providerId: Long): ProviderTraits
}

/**
 * The one place the local renderer's current transcode settings, the network
 * snapshot and provider traits meet the pure planner (D-53/D-60/D-61). The
 * queue entry carries no source metadata and the device-reported sample-rate
 * limit is not read here (D-59), so both planner inputs stay unknown on
 * purpose, exactly as in [PlannedStreamVariants].
 */
class StreamPlanSource @Inject constructor(
    private val settings: TranscodeSettingsStore,
    private val network: NetworkMonitor,
    private val traits: ProviderTraitsLookup,
) {
    suspend fun currentDecision(entry: PlaybackQueueEntry): TranscodeDecision {
        val current = settings.current(TranscodeRendererId.Local)
        return plan(current, network.current(), entry)
    }

    suspend fun plan(
        settings: TranscodeSettings,
        network: NetworkState,
        entry: PlaybackQueueEntry,
    ): TranscodeDecision {
        val slot = TranscodeSlotResolver.streamSlot(network, settings)
        return TranscodePlanner.plan(
            TranscodePlanInput(
                source = TranscodeSource(format = null),
                renderer = RendererCapabilities(supportedFormats = null),
                settings = settings,
                providerTraits = traits.traitsFor(entry.providerId),
                requestKind = slot,
            ),
        )
    }
}

/**
 * Online [StreamResolver] that carries the planned Wi-Fi/cellular
 * bitrate/format (D-61): [TranscodeDecision.DirectPlay] and
 * [TranscodeDecision.Refused] land on the provider's plain stream, while
 * [TranscodeDecision.ServerTranscode] goes through
 * [MediaProvider.resolveTranscodeStream], whose typed fallbacks also land on
 * the plain stream. A positive start position rides the request as a
 * positive-only `timeOffsetSeconds`; when an offset was sent, the returned
 * timeline starts at the offset, so [ResolvedStream.startPositionMs] is 0 and
 * the queue machine keeps song-position bookkeeping itself. Transcode results
 * keep a variant-qualified [StreamKey.cacheKey]; a server-transcode session is
 * marked so a server-default transcode (null/null) can never share the raw
 * `auto:auto` cache entry with direct play (D-57/D-62). Radio always plays the
 * plain live stream. An unknown provider or null URL resolves to null; a
 * failed transcode request falls back to the plain stream, so the queue
 * machine advances as before.
 */
class PlannedProviderStreamResolver @Inject constructor(
    private val providers: MediaProviderLookup,
    private val plans: StreamPlanSource,
    private val logger: OmnisonicLogger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
) : StreamResolver {

    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? =
        resolve(entry, 0L)

    override suspend fun resolve(entry: PlaybackQueueEntry, startPositionMs: Long): ResolvedStream? {
        val provider = providers.providerFor(entry.providerId) ?: return null
        if (entry.isRadioStation) return plain(provider, entry, startPositionMs)
        return when (val decision = plans.currentDecision(entry)) {
            TranscodeDecision.DirectPlay -> plain(provider, entry, startPositionMs)
            is TranscodeDecision.ServerTranscode ->
                transcode(provider, entry, decision, startPositionMs)
            is TranscodeDecision.Refused -> plain(provider, entry, startPositionMs)
        }
    }

    private suspend fun transcode(
        provider: MediaProvider,
        entry: PlaybackQueueEntry,
        decision: TranscodeDecision.ServerTranscode,
        startPositionMs: Long,
    ): ResolvedStream? {
        val requestedPosition = startPositionMs.coerceAtLeast(0L)
        val offsetSeconds = (requestedPosition / 1_000L).toInt().takeIf { it > 0 }
        val resolution = try {
            provider.resolveTranscodeStream(
                entry.externalId,
                decision.maxBitRateKbps,
                decision.format,
                offsetSeconds,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fixed token only, no throwable (D-58); the provider already
            // logged its own typed fallback reason when it had one.
            logger.warn(TAG, "transcode resolve failed; requesting plain stream")
            return plain(provider, entry, startPositionMs)
        }
        val uri = resolution.url ?: return null
        return ResolvedStream(
            entry = entry,
            uri = uri,
            startPositionMs = if (offsetSeconds != null) 0L else requestedPosition,
            requiresNetwork = !isDirect(uri),
            isRadio = false,
            cacheKey = StreamKey(
                entry.providerId,
                entry.externalId,
                decision.maxBitRateKbps,
                decision.format,
                serverTranscoded = resolution.serverTranscoded,
            ).cacheKey,
            transport = if (isDirect(uri)) StreamTransport.DIRECT_URI else StreamTransport.PROVIDER_HTTP,
        )
    }

    private fun plain(
        provider: MediaProvider,
        entry: PlaybackQueueEntry,
        startPositionMs: Long,
    ): ResolvedStream? {
        val uri = runCatching { provider.streamUrl(entry.externalId) }.getOrNull() ?: return null
        return ResolvedStream(
            entry = entry,
            uri = uri,
            startPositionMs = startPositionMs.coerceAtLeast(0L),
            requiresNetwork = !isDirect(uri),
            isRadio = entry.isRadioStation,
            transport = if (isDirect(uri)) StreamTransport.DIRECT_URI else StreamTransport.PROVIDER_HTTP,
        )
    }

    private fun isDirect(uri: String): Boolean =
        uri.startsWith("content://") || uri.startsWith("file://")

    private companion object {
        const val TAG = "PlannedStream"
    }
}
