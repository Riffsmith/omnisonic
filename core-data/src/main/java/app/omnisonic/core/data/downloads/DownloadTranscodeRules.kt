package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.DownloadStreamKey

/**
 * Download-time transcode rules (D-52/D-55). A queue row's explicit variant
 * (bitrate and/or format on its media key) is the planned server-transcode
 * intent; `auto:auto` is a raw download. Row identity, offline paths and
 * `offline_media.sourceKey` never change here: the 192 kbps default only fills
 * a missing bitrate on the wire request.
 */
object DownloadTranscodeRules {
    const val DEFAULT_TRANSCODE_BITRATE_KBPS = 192

    /** True when the row was explicitly planned as a server transcode. */
    fun isTranscoded(key: DownloadStreamKey): Boolean =
        key.transcodeBitrateKbps != null || key.format != null

    /** Wire bitrate: the explicit value, else 192 for a planned transcode, else raw. */
    fun requestBitrateKbps(key: DownloadStreamKey): Int? =
        key.transcodeBitrateKbps ?: if (key.format != null) DEFAULT_TRANSCODE_BITRATE_KBPS else null
}
