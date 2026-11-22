package app.omnisonic.provider.api

/**
 * Why a planned server transcode ended on the plain stream. Fixed tokens only:
 * the value never carries server text, URLs or credentials, so it is safe for
 * logs, rows and UI errors.
 */
enum class TranscodeFallbackReason {
    /** The capability probe advertised no transcode extension, so no decision call is made. */
    EXTENSION_ABSENT,

    /** The decision call answered `canTranscode: false` (or no usable transcode params). */
    SERVER_DECLINED,

    /** The decision call failed: transport, HTTP error, Subsonic error or malformed JSON. */
    DECISION_FAILED,
}

/**
 * One stream URL resolved at the provider seam from a planned transcode request.
 * [url] keeps the provider's signed query parameters by design (same contract as
 * [MediaProvider.streamUrl]); [serverTranscoded] tells whether the URL addresses a
 * server-side transcode session; [fallbackReason] is null when no fallback happened.
 */
data class TranscodeStreamResolution(
    val url: String?,
    val serverTranscoded: Boolean,
    val fallbackReason: TranscodeFallbackReason?,
)
