package app.omnisonic.shell

import androidx.compose.ui.test.junit4.createComposeRule
import app.omnisonic.feature.settings.sync.LibrarySyncStepRoute
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring check for the sync route: a step that is already complete auto-advances
 * and the route's event collector calls `onComplete`. The deterministic
 * late-subscriber regression lives in `LibrarySyncStepViewModelTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySyncStepRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anAlreadyCompleteStepReportsCompletion() {
        val viewModel = testLibrarySyncStepViewModel(
            control = FakeLibrarySyncControl(
                initialProgress = null,
                initialProviders = listOf(syncDoneProvider()),
            ),
        )
        var completed = false

        composeRule.setContent {
            OmnisonicTheme {
                LibrarySyncStepRoute(viewModel = viewModel, onComplete = { completed = true })
            }
        }
        composeRule.waitForIdle()

        assertThat(completed).isTrue()
    }
}
