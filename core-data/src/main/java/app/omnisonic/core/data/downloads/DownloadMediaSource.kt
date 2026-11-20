package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderAuthException
import app.omnisonic.provider.api.ProviderFatalException
import app.omnisonic.provider.api.ProviderHttpError
import app.omnisonic.provider.api.ProviderTransportException
import java.io.IOException
import app.omnisonic.provider.api.ProviderRegistry
import okhttp3.OkHttpClient

/** The identity a queue row carries for one attempt (parsed from its media key). */
data class DownloadMediaRequest(
    val providerId: Long,
    val externalId: String,
    val transcodeBitrateKbps: Int?,
    val format: String?,
)

/** One candidate endpoint with the registry-owned MEDIA client; never a bare/plain client. */
data class DownloadMediaEndpoint(
    val url: String,
    val client: OkHttpClient,
)

sealed interface DownloadMediaResolution {

    /**
     * [providerPath] is the song snapshot path (or the fresh `songDetails`
     * fallback) that keys the offline variant; [endpoints] are ordered for
     * connection failover and always share one representation.
     */
    data class Available(
        val providerPath: String,
        val endpoints: List<DownloadMediaEndpoint>,
    ) : DownloadMediaResolution

    data object ProviderMissing : DownloadMediaResolution

    data object DownloadUnsupported : DownloadMediaResolution

    /** Provider row exists but its credentials/client could not be rebuilt. */
    data object CredentialsUnavailable : DownloadMediaResolution

    data object MediaPathMissing : DownloadMediaResolution

    data object MediaPathTransport : DownloadMediaResolution

    data object MediaPathRejected : DownloadMediaResolution

    data object EndpointMissing : DownloadMediaResolution
}

fun interface DownloadMediaSource {
    suspend fun resolve(request: DownloadMediaRequest): DownloadMediaResolution
}

/**
 * Auth outcomes feed the provider status tracker: a raw media rejection is
 * reported uncounted, and an authenticated raw response clears a stale marker
 * (Phase 7 task 3).
 */
interface DownloadAuthReporting {
    suspend fun authRejected(providerId: Long)
    suspend fun authSucceeded(providerId: Long)
}

class ProviderAuthReporting(private val tracker: ProviderStatusTracker) : DownloadAuthReporting {

    override suspend fun authRejected(providerId: Long) = tracker.markAuthFailed(providerId)

    override suspend fun authSucceeded(providerId: Long) = tracker.clearAuthFailed(providerId)
}

/**
 * Production resolution over the provider registry. Capability comes from the
 * registry-backed traits (never the permissive default source), the bytes ride
 * the registry MEDIA client, and a provider that cannot be rebuilt fails closed
 * instead of falling back to a plain client.
 */
class ProviderMediaSource(
    private val database: OmnisonicDatabase,
    private val clients: ProviderClientRegistry,
    private val providers: ProviderRegistry,
) : DownloadMediaSource {

    override suspend fun resolve(request: DownloadMediaRequest): DownloadMediaResolution {
        val row = database.providerDao().selectById(request.providerId)
            ?: return DownloadMediaResolution.ProviderMissing
        if (!providers.traitsFor(row.api).supportsDownload) {
            return DownloadMediaResolution.DownloadUnsupported
        }
        val provider = clients.providerFor(request.providerId)
            ?: return DownloadMediaResolution.CredentialsUnavailable
        val client = clients.httpClientFor(request.providerId)
            ?: return DownloadMediaResolution.CredentialsUnavailable
        val providerPath = try {
            mediaPath(provider, request)
        } catch (_: ProviderAuthException) {
            return DownloadMediaResolution.CredentialsUnavailable
        } catch (_: ProviderTransportException) {
            return DownloadMediaResolution.MediaPathTransport
        } catch (_: IOException) {
            return DownloadMediaResolution.MediaPathTransport
        } catch (_: ProviderHttpError) {
            return DownloadMediaResolution.MediaPathRejected
        } catch (_: ProviderFatalException) {
            return DownloadMediaResolution.MediaPathRejected
        } ?: return DownloadMediaResolution.MediaPathMissing
        val urls = provider
            .downloadEndpoints(request.externalId, request.transcodeBitrateKbps, request.format)
            .ifEmpty { listOfNotNull(provider.downloadUrl(request.externalId, request.transcodeBitrateKbps, request.format)) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (urls.isEmpty()) return DownloadMediaResolution.EndpointMissing
        return DownloadMediaResolution.Available(
            providerPath = providerPath,
            endpoints = urls.map { url -> DownloadMediaEndpoint(url, client) },
        )
    }

    private suspend fun mediaPath(provider: MediaProvider, request: DownloadMediaRequest): String? {
        val snapshot = database.songDao()
            .selectByProviderAndExternal(request.providerId, request.externalId)
            ?.filePath
            ?.takeIf { it.isNotBlank() }
        if (snapshot != null) return snapshot
        val details = provider.songDetails(request.externalId)
        return details?.filePath?.takeIf { it.isNotBlank() }
    }
}
