package app.omnisonic.shell

import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.entity.ProviderEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the fresh-install Home empty state: `hasProvider` follows the
 * `providers` table, so saving the first provider clears the setup button in
 * the same process instead of only after a restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellViewModelTest {

    @Before
    fun setUp() {
        // `viewModelScope` is Main; Unconfined makes the eager `stateIn` collect
        // immediately and deterministically in a plain (non-Compose) test.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun hasProviderFollowsProviderRows() = runBlocking {
        val db = OmnisonicDatabaseFactory(
            ApplicationProvider.getApplicationContext(),
        ).openInMemory()
        try {
            val viewModel = ShellViewModel(
                shellPreferences = tempShellStore("shell-vm"),
                database = db,
            )
            assertThat(withTimeout(TIMEOUT_MS) { viewModel.state.first { it != null } }!!.hasProvider)
                .isFalse()

            db.providerDao().upsert(providerRow())

            assertThat(
                withTimeout(TIMEOUT_MS) { viewModel.state.first { it?.hasProvider == true } }!!
                    .hasProvider,
            ).isTrue()
        } finally {
            db.close()
        }
    }

    private fun providerRow(): ProviderEntity = ProviderEntity(
        id = 1,
        name = "Home",
        api = "api-test",
        primaryScheme = "https",
        primaryHost = "host.example",
        primaryPort = 443,
        primaryPath = "/",
        username = "user",
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
