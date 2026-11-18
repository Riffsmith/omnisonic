package app.omnisonic.playback.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking

/**
 * Pins the real session-artwork encode: the bytes handed to the media session
 * must be a decodable PNG of the loaded bitmap at the request size, and that
 * size must stay below media3's session artwork cap
 * (`config_mediaMetadataBitmapMaxSize`, 320 dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionArtworkPngTest {

    @Test
    fun `encodes the loaded bitmap as a decodable png at the same dimensions`() = runBlocking {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)

        val png = encodeSessionArtworkPng(bitmap)

        assertPngSignature(png)
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        checkNotNull(decoded) { "encoded artwork does not decode" }
        check(decoded.width == SIZE_PX && decoded.height == SIZE_PX) {
            "decoded ${decoded.width}x${decoded.height}, expected $SIZE_PX"
        }
    }

    @Test
    fun `the request size stays within the media3 session artwork cap`() {
        // 320 dp from AOSP config_mediaMetadataBitmapMaxSize at the 420 dpi
        // test density: 320 * 2.625 = 840 px, comfortably above 512 px.
        val capPx = (SESSION_ARTWORK_CAP_DP * 2.625f).toInt()
        check(NotificationArtworkMetadata.REQUEST_SIZE_PX <= capPx) {
            "request ${NotificationArtworkMetadata.REQUEST_SIZE_PX}px exceeds session cap ${capPx}px"
        }
    }

    private fun assertPngSignature(bytes: ByteArray) {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        check(bytes.size > signature.size) { "encoded artwork is empty" }
        check(bytes.copyOfRange(0, signature.size).contentEquals(signature)) {
            "encoded artwork is not a PNG"
        }
    }

    private companion object {
        const val SIZE_PX = NotificationArtworkMetadata.REQUEST_SIZE_PX
        const val SESSION_ARTWORK_CAP_DP = 320
    }
}
