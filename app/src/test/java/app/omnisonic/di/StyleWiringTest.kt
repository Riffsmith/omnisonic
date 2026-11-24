package app.omnisonic.di

import app.omnisonic.core.data.style.StyleProfileState
import app.omnisonic.core.data.style.StyleProfileStore
import app.omnisonic.core.data.style.StyleSwitchResult
import app.omnisonic.style.CornerScale
import app.omnisonic.style.DefaultStyleTokens
import app.omnisonic.style.ProfileStyleTokenSource
import app.omnisonic.style.StyleTokenSource
import app.omnisonic.style.StyleTokens
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production-graph proof for the style token seam (`docs/omnisonic/style/style-profiles.md`
 * §Wiring, D-79): Hilt resolves the profile-backed source from the shipped
 * built-ins and the active-profile store, and one switch republishes once.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
class StyleWiringTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var tokenSource: StyleTokenSource

    @Inject
    lateinit var profileSource: ProfileStyleTokenSource

    @Inject
    lateinit var profileStore: StyleProfileStore

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun seamResolvesTheProfileBackedSource() {
        assertThat(tokenSource).isInstanceOf(ProfileStyleTokenSource::class.java)
        assertThat(tokenSource).isSameInstanceAs(profileSource)
    }

    @Test
    fun aFreshInstallResolvesTheApprovedBaselineTokens() = runBlocking {
        assertThat(StyleWiringModule.DEFAULT_STYLE_ID).isEqualTo("builtin-default")
        assertThat(profileStore.activeId.first()).isNull()

        val state = withTimeout(5_000) { profileStore.activeProfile.first() }
        assertThat(state).isInstanceOf(StyleProfileState.Resolved::class.java)
        assertThat((state as StyleProfileState.Resolved).document.id).isEqualTo("builtin-default")

        val tokens = withTimeout(5_000) { profileSource.tokenFlow.first() }
        assertThat(tokens).isEqualTo(DefaultStyleTokens)
    }

    @Test
    fun flowEmitsTheResolvedTokensForTheActiveProfile() = runBlocking {
        val switched = profileStore.switch("builtin-tablet-wide")
        assertThat(switched).isInstanceOf(StyleSwitchResult.Switched::class.java)

        val tokens = withTimeout(5_000) { profileSource.tokenFlow.first() }

        assertThat(tokens.layout.gridColumnsPortrait).isEqualTo(5)
        assertThat(tokens.layout.gridColumnsLandscape).isEqualTo(8)
        assertThat(tokens.shapes.cornerScale).isEqualTo(CornerScale.LARGE)
    }

    @Test
    fun aSwitchProducesExactlyOneNewEmission() = runBlocking {
        val compact = profileStore.switch("builtin-compact-list")
        assertThat(compact).isInstanceOf(StyleSwitchResult.Switched::class.java)

        val emissions = Channel<StyleTokens>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            profileSource.tokenFlow.collect { emissions.send(it) }
        }
        val first = withTimeout(5_000) { emissions.receive() }
        assertThat(first.layout.gridColumnsPortrait).isEqualTo(4)

        val large = profileStore.switch("builtin-large-cover")
        assertThat(large).isInstanceOf(StyleSwitchResult.Switched::class.java)
        val second = withTimeout(5_000) { emissions.receive() }
        assertThat(second.layout.gridColumnsPortrait).isEqualTo(2)

        delay(250)
        assertThat(emissions.tryReceive().isFailure).isTrue()
        collector.cancel()
    }
}
