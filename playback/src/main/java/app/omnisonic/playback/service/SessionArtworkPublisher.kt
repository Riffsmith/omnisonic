package app.omnisonic.playback.service

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Publishes cover artwork onto the exposed session metadata so Android 13+
 * SystemUI stops rendering the platform-reduced notification large icon
 * (`Notification.reduceImageSizes` shrinks that icon to 48 dp at build time,
 * while the media card reads session artwork first).
 *
 * Implementations post to the player's application looper — never apply the
 * change inline from a notification build, because [androidx.media3.common.SimpleBasePlayer.invalidateState]
 * is application-thread-only and re-entering the notification build is wrong —
 * and are idempotent for an unchanged identity and byte-array instance.
 */
internal fun interface SessionArtworkPublisher {
    fun publishSessionArtwork(identity: NotificationArtworkIdentity, png: ByteArray)
}

/**
 * Encodes the already-loaded cover bitmap as PNG for the session payload. Runs
 * on [Dispatchers.Default] so the media session thread never encodes, and is
 * called once per emitted [Bitmap] (the provider caches the bytes for the
 * identity). Lossless PNG avoids a second lossy generation; the session
 * bitmap loader decodes either format.
 */
internal suspend fun encodeSessionArtworkPng(bitmap: Bitmap): ByteArray =
    withContext(Dispatchers.Default) {
        ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "session artwork PNG encode failed"
            }
            stream.toByteArray()
        }
    }
