package app.omnisonic.di

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.navidrome.SubsonicClient
import com.google.common.truth.Truth.assertThat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import java.net.ServerSocket
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Production-graph proof for the provider wiring: the real Hilt component owns
 * one Android-backed logger and one [ProviderClientRegistry], the provider
 * rides the registry's API client, and a learned block preference lands in the
 * real DAO without touching the row's `updatedAt` cache key.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
class ProviderWiringTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var database: OmnisonicDatabase

    @Inject
    lateinit var clients: ProviderClientRegistry

    @Inject
    lateinit var logger: OmnisonicLogger

    @Before
    fun setUp() {
        hiltRule.inject()
        ShadowLog.reset()
        runBlocking {
            database.providerDao().selectAll().forEach { database.providerDao().deleteById(it.id) }
        }
    }

    @Test
    fun `learned preferred block persists through the production dao`() = runBlocking {
        val row = seedUnreachableRemoteProvider(updatedAt = 2_000)
        val provider = clients.providerFor(row.id) as SubsonicClient

        repeat(2) { runCatching { provider.probe() } }

        val persisted = database.providerDao().selectById(row.id)!!
        assertThat(persisted.lastWorkingBlock).isEqualTo(ProviderConnection.SECONDARY_BLOCK)
        // The registry's default client factories reuse the shared logger.
        assertThat(ShadowLog.getLogs().map { it.tag }).contains("HttpClient")
    }

    @Test
    fun `persisting the preferred block leaves updatedAt alone so the cache entry survives`() = runBlocking {
        val row = seedUnreachableRemoteProvider(updatedAt = 2_000)
        val provider = clients.providerFor(row.id) as SubsonicClient
        val apiClient = clients.apiClientFor(row.id)

        repeat(2) { runCatching { provider.probe() } }

        val persisted = database.providerDao().selectById(row.id)!!
        assertThat(persisted.lastWorkingBlock).isEqualTo(ProviderConnection.SECONDARY_BLOCK)
        assertThat(persisted.updatedAt).isEqualTo(row.updatedAt)
        assertThat(clients.providerFor(row.id)).isSameInstanceAs(provider)
        assertThat(clients.apiClientFor(row.id)).isSameInstanceAs(apiClient)
        assertThat(provider.http).isSameInstanceAs(apiClient)
    }

    @Test
    fun `one shared logger writes to logcat`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fromEntryPoint = EntryPointAccessors.fromApplication(context, LoggerEntryPoint::class.java)
            .omnisonicLogger()

        logger.warn("WiringTest", "shared logger reached logcat")

        val entry = ShadowLog.getLogs().last { it.tag == "WiringTest" }
        assertThat(fromEntryPoint).isSameInstanceAs(logger)
        assertThat(entry.type).isEqualTo(Log.WARN)
        assertThat(entry.msg).isEqualTo("shared logger reached logcat")
    }

    @Test
    fun `registry warnings are emitted through the shared android logger`() = runBlocking {
        val row = ProviderEntity(
            name = "broken",
            api = "subsonic",
            primaryHost = " ",
            createdAt = 1_000,
            updatedAt = 1_000,
        )
        database.providerDao().upsert(row)
        val providerId = database.providerDao().selectAll().single().id
        ShadowLog.reset()

        assertThat(clients.providerFor(providerId)).isNull()

        assertThat(ShadowLog.getLogs().map { it.tag }).contains("ProviderClientRegistry")
    }

    private suspend fun seedUnreachableRemoteProvider(updatedAt: Long): ProviderEntity {
        val (primaryPort, secondaryPort) = closedPorts()
        database.providerDao().upsert(
            ProviderEntity(
                name = "wired",
                api = "subsonic",
                primaryScheme = "http",
                primaryHost = "127.0.0.1",
                primaryPort = primaryPort,
                secondaryScheme = "http",
                secondaryHost = "127.0.0.1",
                secondaryPort = secondaryPort,
                useSecondary = true,
                username = "user",
                createdAt = 1_000,
                updatedAt = updatedAt,
            ),
        )
        return database.providerDao().selectAll().single()
    }

    /** Two distinct ports that were just released, so connections are refused. */
    private fun closedPorts(): Pair<Int, Int> =
        ServerSocket(0).use { first ->
            ServerSocket(0).use { second -> first.localPort to second.localPort }
        }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LoggerEntryPoint {
        fun omnisonicLogger(): OmnisonicLogger
    }
}
