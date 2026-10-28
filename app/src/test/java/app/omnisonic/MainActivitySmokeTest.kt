package app.omnisonic

import androidx.compose.ui.platform.ComposeView
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
class MainActivitySmokeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun activityLaunchesToComposeSurface() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertThat(activity.isFinishing).isFalse()

        val content = activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)

        assertThat(content.childCount).isGreaterThan(0)
        assertThat(content.getChildAt(0)).isInstanceOf(ComposeView::class.java)
    }
}
