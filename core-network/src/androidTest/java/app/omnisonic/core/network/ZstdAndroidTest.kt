package app.omnisonic.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZstdAndroidTest {

    @Test
    fun androidArtifactLoadsNativeZstdAndDecodesPayload() {
        val payload = "omnisonic-android-zstd".toByteArray()
        val compressed = Zstd.compress(payload)

        val decoded = ZstdInputStream(compressed.inputStream()).use { it.readBytes() }

        assertEquals(payload.toList(), decoded.toList())
    }
}
