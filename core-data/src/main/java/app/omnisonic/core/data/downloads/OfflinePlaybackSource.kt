package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.OfflineFileDao
import java.io.File
import java.net.URI

/** A stored offline file that is readable now; [uri] is a direct `file://` source. */
data class OfflinePlaybackFile(
    val id: Long,
    val sourceKey: String,
    val uri: String,
    val sizeBytes: Long,
    val title: String?,
    val bitrateKbps: Int?,
    val downloadedAtMs: Long?,
)

/**
 * Read seam for offline-first playback. A row is offered only while its
 * variant-qualified file is a non-empty regular file under the active root, so
 * an unmounted volume or a missing file is a fallback signal, never a
 * playable source. The lookup is provider + external id, so a deleted or
 * re-titled library song cannot orphan a downloaded file; display metadata
 * comes from the offline row itself.
 */
class OfflinePlaybackSource(
    private val rows: OfflineFileDao,
    private val storage: OfflineStorage,
) {

    suspend fun filesFor(providerId: Long, externalId: String): List<OfflinePlaybackFile> =
        rows.selectByProviderAndExternal(providerId, externalId, ProfileScope.Unrestricted)
            .mapNotNull { row ->
                val file = storage.resolveStoredPath(row.offlinePath) ?: return@mapNotNull null
                if (!file.isFile) return@mapNotNull null
                val size = file.length()
                if (size <= 0L) return@mapNotNull null
                OfflinePlaybackFile(
                    id = row.id,
                    sourceKey = row.sourceKey,
                    uri = directUri(file),
                    sizeBytes = size,
                    title = row.title,
                    bitrateKbps = row.bitrateKbps,
                    downloadedAtMs = row.downloadedAt,
                )
            }

    private fun directUri(file: File): String = URI("file", "", file.absolutePath, null).toString()
}
