package app.omnisonic.provider.api

/**
 * Runtime capability profile a provider factory declares. Never persisted.
 * Defaults match the Subsonic family: full library sync, downloads, server
 * transcode negotiation and user-data reporting. Providers with narrower
 * capabilities override the factory's [MediaProviderFactory.traits].
 */
data class ProviderTraits(
    val hasLibrarySync: Boolean = true,
    val supportsDownload: Boolean = true,
    val supportsServerTranscode: Boolean = true,
    val reportsUserData: Boolean = true,
)

/**
 * Outcome of a capability-gated outbox enqueue. Providers whose traits report
 * `reportsUserData = false` are skipped politely instead of enqueueing rows a
 * drain cycle would only discard.
 */
sealed interface OutboxEnqueueResult {
    data object Enqueued : OutboxEnqueueResult

    /** Nothing was written; the provider does not report user data to a remote. */
    data object SkippedNoUserDataReporting : OutboxEnqueueResult
}

/**
 * Outcome of a capability-gated download enqueue. Providers whose traits report
 * `supportsDownload = false` reject the request with a typed result instead of
 * throwing; a run-gate block (offline, not on required Wi-Fi) defers the
 * request with nothing written.
 */
sealed interface DownloadEnqueueResult {
    data object Enqueued : DownloadEnqueueResult

    /** Nothing was enqueued; the provider has no download path. */
    data object SkippedDownloadUnsupported : DownloadEnqueueResult

    /** Nothing was written; the run gate blocked the provider ([status] != OK). */
    data class BlockedByStatus(val status: ProviderStatus) : DownloadEnqueueResult
}
