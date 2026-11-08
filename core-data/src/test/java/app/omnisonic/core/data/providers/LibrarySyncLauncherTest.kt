package app.omnisonic.core.data.providers

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.api.ProviderTraits
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val launcherKey: SecretKey by lazy {
    KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySyncLauncherTest : DatabaseTestBase() {

    private val codec = AesGcmCredentialCodec(
        moshi = OmnisonicJson.moshi,
        keySource = SecretKeySource { launcherKey },
    )

    private fun launcher(): LibrarySyncLauncher = LibrarySyncLauncher(
        database = db,
        clients = ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(SubsonicProviderFactory())),
            mapper = ProviderConfigMapper(codec, OmnisonicJson.moshi),
            logger = silentTestLogger,
        ),
        traitsFor = { ProviderTraits() },
        scope = CoroutineScope(Dispatchers.Unconfined),
        logger = silentTestLogger,
    )

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
}
