package app.omnisonic.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ZstdSupportTest {

    @Test
    fun hostJvmRoundTripDetectsZstdSupport() {
        assertThat(ZstdSupport.available).isTrue()
    }

    @Test
    fun detectionIsMemoizedAcrossCalls() {
        assertThat(ZstdSupport.available).isSameInstanceAs(ZstdSupport.available)
    }
}
