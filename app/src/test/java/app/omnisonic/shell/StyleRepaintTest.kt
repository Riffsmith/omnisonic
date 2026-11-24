package app.omnisonic.shell

import android.app.Activity
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.style.DefaultStyleTokens
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.ProfileStyleTokenSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live repaint proof (`docs/omnisonic/style/style-profiles.md` §Repaint, D-80):
 * the theme follows the profile-backed flow, so a switch changes a token-driven
 * visible value in place, with no activity recreation, and an unusable profile
 * leaves the last good value untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class StyleRepaintTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun document(id: String): StyleDocument {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.assets.open("styles/$id.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val result = StyleDecoder.decode(text)
        assertThat(result).isInstanceOf(StyleDecodeResult.Decoded::class.java)
        return (result as StyleDecodeResult.Decoded).document
    }

    private fun source(documents: MutableStateFlow<StyleDocument?>) =
        ProfileStyleTokenSource(activeDocument = documents)

    @Composable
    private fun ColumnsProbe() {
        val tokens = OmnisonicTheme.tokens
        Text("portrait=${tokens.layout.gridColumnsPortrait} landscape=${tokens.layout.gridColumnsLandscape}")
    }

    @Test
    fun aProfileSwitchRepaintsTheTokenDrivenValue() {
        val documents = MutableStateFlow<StyleDocument?>(document("builtin-compact-list"))
        composeRule.setContent { OmnisonicTheme(source(documents)) { ColumnsProbe() } }
        composeRule.onNodeWithText("portrait=4 landscape=6").assertExists()

        documents.value = document("builtin-large-cover")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("portrait=2 landscape=3").assertExists()
    }

    @Test
    fun aProfileSwitchRepaintsWithoutRecreatingTheComposition() {
        val documents = MutableStateFlow<StyleDocument?>(document("builtin-compact-list"))
        val hosts = mutableListOf<Activity>()
        val markers = mutableListOf<Any>()
        composeRule.setContent {
            OmnisonicTheme(source(documents)) {
                hosts += LocalContext.current as Activity
                markers += remember { Any() }
                ColumnsProbe()
            }
        }
        composeRule.onNodeWithText("portrait=4 landscape=6").assertExists()

        documents.value = document("builtin-large-cover")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("portrait=2 landscape=3").assertExists()

        assertThat(hosts.toSet()).hasSize(1)
        assertThat(markers.toSet()).hasSize(1)
    }

    @Test
    fun aRejectedProfileLeavesTheVisibleValueUnchanged() {
        val documents = MutableStateFlow<StyleDocument?>(document("builtin-compact-list"))
        composeRule.setContent { OmnisonicTheme(source(documents)) { ColumnsProbe() } }
        composeRule.onNodeWithText("portrait=4 landscape=6").assertExists()

        documents.value = null
        composeRule.waitForIdle()

        composeRule.onNodeWithText("portrait=4 landscape=6").assertExists()
    }

    @Test
    fun anUnavailableStartShowsTheSourceFallbackUntilTheFirstDocument() {
        val documents = MutableStateFlow<StyleDocument?>(null)
        composeRule.setContent { OmnisonicTheme(source(documents)) { ColumnsProbe() } }

        composeRule.onNodeWithText(
            "portrait=${DefaultStyleTokens.layout.gridColumnsPortrait} " +
                "landscape=${DefaultStyleTokens.layout.gridColumnsLandscape}",
        ).assertExists()

        documents.value = document("builtin-tablet-wide")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("portrait=5 landscape=8").assertExists()
    }

    @Test
    fun switchingBackRepaintsTheEarlierValueAgain() {
        val documents = MutableStateFlow<StyleDocument?>(document("builtin-compact-list"))
        composeRule.setContent { OmnisonicTheme(source(documents)) { ColumnsProbe() } }
        composeRule.onNodeWithText("portrait=4 landscape=6").assertExists()

        documents.value = document("builtin-large-cover")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("portrait=2 landscape=3").assertExists()

        documents.value = document("builtin-compact-list")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("portrait=4 landscape=6").assertExists()
    }

    @Test
    fun everyBuiltInProfileResolvesToItsOwnRepaint() {
        val documents = MutableStateFlow<StyleDocument?>(document("builtin-compact-list"))
        composeRule.setContent { OmnisonicTheme(source(documents)) { ColumnsProbe() } }
        composeRule.onNodeWithText("portrait=4 landscape=6").assertExists()

        documents.value = document("builtin-tablet-wide")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("portrait=5 landscape=8").assertExists()

        documents.value = document("builtin-large-cover")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("portrait=2 landscape=3").assertExists()
    }

    @Test
    fun theDefaultThemeStillServesTheDefaultStyle() {
        composeRule.setContent { OmnisonicTheme { ColumnsProbe() } }

        composeRule.onNodeWithText(
            "portrait=${DefaultStyleTokens.layout.gridColumnsPortrait} " +
                "landscape=${DefaultStyleTokens.layout.gridColumnsLandscape}",
        ).assertExists()
    }
}
