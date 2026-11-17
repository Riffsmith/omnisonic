package app.omnisonic.playback.service

import android.graphics.Bitmap
import android.os.Bundle
import androidx.media3.common.MediaMetadata

/**
 * Cover identity the notification can resolve without a URL: provider cover
 * URLs are credential-salted and provider-routed, so the metadata carries the
 * identity and the loader resolves it through the app's cover pipeline.
 */
data class NotificationArtworkIdentity(
    val providerId: Long,
    val imageId: String,
    val sizePx: Int = NotificationArtworkMetadata.REQUEST_SIZE_PX,
)

/**
 * Loads notification cover art. Implementations must do their work off the
 * caller's thread: the provider calls this from the media session thread and
 * only ever builds notifications there.
 */
fun interface NotificationArtworkLoader {
    suspend fun load(identity: NotificationArtworkIdentity): Bitmap?
}

/** Extras keys that carry [NotificationArtworkIdentity] on the exposed metadata. */
object NotificationArtworkMetadata {

    const val EXTRA_PROVIDER_ID = "app.omnisonic.playback.artwork.providerId"
    const val EXTRA_IMAGE_ID = "app.omnisonic.playback.artwork.imageId"
    const val EXTRA_SIZE_PX = "app.omnisonic.playback.artwork.sizePx"

    /** Bucketed request size (D-17): the panel never needs more than this. */
    const val REQUEST_SIZE_PX = 512

    fun extras(
        providerId: Long,
        imageId: String,
        sizePx: Int = REQUEST_SIZE_PX,
    ): Bundle = Bundle().apply {
        putLong(EXTRA_PROVIDER_ID, providerId)
        putString(EXTRA_IMAGE_ID, imageId)
        putInt(EXTRA_SIZE_PX, sizePx)
    }

    fun identityFrom(metadata: MediaMetadata): NotificationArtworkIdentity? {
        val extras = metadata.extras ?: return null
        val imageId = extras.getString(EXTRA_IMAGE_ID) ?: return null
        if (!extras.containsKey(EXTRA_PROVIDER_ID)) return null
        return NotificationArtworkIdentity(
            providerId = extras.getLong(EXTRA_PROVIDER_ID),
            imageId = imageId,
            sizePx = extras.getInt(EXTRA_SIZE_PX, REQUEST_SIZE_PX),
        )
    }
}
