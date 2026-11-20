package app.omnisonic.downloads

import androidx.test.core.app.ApplicationProvider
import app.omnisonic.MainActivity
import app.omnisonic.feature.downloads.service.DownloadService
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
class DownloadAppForegroundTriggerTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `app foreground requests the download service`() {
        Robolectric.buildActivity(MainActivity::class.java).setup()

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .allStartedServices
        assertThat(started.map { it.component?.className })
            .contains(DownloadService::class.java.name)
        assertThat(started.single { it.component?.className == DownloadService::class.java.name }.action)
            .isEqualTo(DownloadService.ACTION_START)
    }
}
