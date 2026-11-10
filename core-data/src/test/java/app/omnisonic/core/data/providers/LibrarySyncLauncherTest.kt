package app.omnisonic.core.data.providers

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.core.data.sync.LibrarySyncLastResult
import app.omnisonic.core.data.sync.LibrarySyncOutcome
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.core.data.sync.SubsonicLibrarySyncer
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.api.ProviderTraits
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val launcherKey: SecretKey by lazy {
    KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
}

/** Any wallet-sized provider that reports the fake api the test row owns. */
private class FakeProviderFactory(private val provider: MediaProvider) : MediaProviderFactory {
    override fun apiType(): String = "faked"

    override fun create(connection: ProviderConnection, apiClient: OkHttpClient): MediaProvider = provider
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySyncLauncherTest : DatabaseTestBase() {

    private val codec = AesGcmCredentialCodec(
        moshi = OmnisonicJson.moshi,
        keySource = SecretKeySource { launcherKey },
    )

    private fun launcher(
        providerRegistry: ProviderRegistry = ProviderRegistry(listOf(SubsonicProviderFactory())),
        traitsFor: (String) -> ProviderTraits = { ProviderTraits() },
        statuses: LibrarySyncStatusRepository = LibrarySyncStatusRepository(),
    ): LibrarySyncLauncher = LibrarySyncLauncher(
        database = db,
        clients = ProviderClientRegistry(
            database = db,
            providerRegistry = providerRegistry,
            mapper = ProviderConfigMapper(codec, OmnisonicJson.moshi),
            logger = silentTestLogger,
        ),
        traitsFor = traitsFor,
        scope = CoroutineScope(Dispatchers.Unconfined),
        logger = silentTestLogger,
        statuses = statuses,
    )

    private suspend fun seedProvider(api: String, name: String = "row", displayOrder: Int = 0): Long {
        providerDao.upsert(
            ProviderEntity(
                name = name,
                api = api,
                displayOrder = displayOrder,
                primaryScheme = "http",
                primaryHost = "127.0.0.1",
                primaryPort = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        return providerDao.selectAll().last().id
    }

    @Test
    fun `unknown provider id is a no-op`() = runDbTest {
        launcher().run(4242L)
    }

    @Test
    fun `an unloadable provider row is skipped without throwing`() = runDbTest {
        providerDao.upsert(
            ProviderEntity(
                name = "broken",
                api = "subsonic",
                primaryScheme = "https",
                primaryHost = " ",
                primaryPort = 443,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val id = providerDao.selectAll().single().id

        launcher().run(id)
    }

    @Test
    fun `full and diff walks record their own timestamps`() = runDbTest {
        val provider = mockk<MediaProvider>(relaxed = true)
        val statuses = LibrarySyncStatusRepository()
        val launcher = launcher(
            providerRegistry = ProviderRegistry(listOf(FakeProviderFactory(provider))),
            statuses = statuses,
        )
        val id = seedProvider(api = "faked")

        launcher.run(id, LibrarySyncKind.FULL)
        val afterFull = syncStatusDao.selectByProvider(id)!!
        assertThat(afterFull.lastFullSyncAt).isNotNull()
        assertThat(afterFull.lastDiffSyncAt).isNull()
        assertThat(statuses.progress.value?.outcome).isEqualTo(LibrarySyncOutcome.COMPLETED)

        launcher.run(id, LibrarySyncKind.DIFF)
        val afterDiff = syncStatusDao.selectByProvider(id)!!
        assertThat(afterDiff.lastFullSyncAt).isEqualTo(afterFull.lastFullSyncAt)
        assertThat(afterDiff.lastDiffSyncAt).isNotNull()
    }

    @Test
    fun `providers report recorded outcomes and library-sync support`() = runDbTest {
        val remoteId = seedProvider(api = "subsonic", name = "Home", displayOrder = 0)
        val localId = seedProvider(api = "local", name = "On device", displayOrder = 1)
        val freshId = seedProvider(api = "subsonic", name = "Spare", displayOrder = 2)
        syncStatusDao.upsert(
            SyncStatusEntity(
                providerId = remoteId,
                lastFullSyncAt = 5_000,
                lastSyncResult = SubsonicLibrarySyncer.RESULT_OK,
                lastSyncMessage = "ok",
                lastSyncCount = 120,
                lastSyncDurationMs = 900,
            ),
        )
        syncStatusDao.upsert(
            SyncStatusEntity(
                providerId = localId,
                lastDiffSyncAt = 6_000,
                lastSyncResult = SubsonicLibrarySyncer.RESULT_FAILED,
                lastSyncMessage = "offline",
            ),
        )

        val launcher = launcher(
            traitsFor = { api ->
                if (api == "local") ProviderTraits(hasLibrarySync = false) else ProviderTraits()
            },
        )
        val providers = launcher.providers()

        assertThat(providers.map { it.name }).containsExactly("Home", "On device", "Spare").inOrder()
        val remote = providers[0]
        assertThat(remote.providerId).isEqualTo(remoteId)
        assertThat(remote.supportsLibrarySync).isTrue()
        assertThat(remote.lastFullSyncAt).isEqualTo(5_000)
        assertThat(remote.lastResult).isEqualTo(LibrarySyncLastResult.COMPLETED)
        assertThat(remote.lastSyncAt).isEqualTo(5_000)
        assertThat(remote.lastCount).isEqualTo(120)
        assertThat(remote.lastDurationMs).isEqualTo(900)

        val local = providers[1]
        assertThat(local.supportsLibrarySync).isFalse()
        assertThat(local.lastResult).isEqualTo(LibrarySyncLastResult.FAILED)
        assertThat(local.lastSyncAt).isEqualTo(6_000)
        assertThat(local.lastMessage).isEqualTo("offline")

        val fresh = providers[2]
        assertThat(fresh.providerId).isEqualTo(freshId)
        assertThat(fresh.lastResult).isEqualTo(LibrarySyncLastResult.NEVER)
        assertThat(fresh.lastSyncAt).isNull()
    }
}
