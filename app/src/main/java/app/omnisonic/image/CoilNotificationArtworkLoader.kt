package app.omnisonic.image

import android.content.Context
import android.graphics.Bitmap
import app.omnisonic.playback.service.NotificationArtworkIdentity
import app.omnisonic.playback.service.NotificationArtworkLoader
import app.omnisonic.provider.api.CoverImageKey
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification artwork through the app's single cover pipeline: the request
 * resolves to the provider-routed URL, shares the cover cache and respects the
 * bucketed request size. Hardware bitmaps are refused because the notification
 * large icon must be a software bitmap.
 */
@Singleton
class CoilNotificationArtworkLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) : NotificationArtworkLoader {

    override suspend fun load(identity: NotificationArtworkIdentity): Bitmap? =
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(CoverImageKey(identity.providerId, identity.imageId, identity.sizePx))
                .allowHardware(false)
                .build()
            ((imageLoader.execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
        }
}
