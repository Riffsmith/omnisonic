package app.omnisonic.core.data.providers

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.api.ProviderTraits
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import app.omnisonic.provider.local.LocalMediaProvider
import app.omnisonic.provider.local.LocalMediaProviderFactory
import com.google.common.truth.Truth.assertThat
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val testKey: SecretKey by lazy {
    KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalProviderPersistenceTest {

    private lateinit var appContext: Context

    @Before
    fun prepare() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    private val mapper = ProviderConfigMapper(
        codec = AesGcmCredentialCodec(
            keySource = SecretKeySource { testKey },
            moshi = OmnisonicJson.moshi,
        ),
    )

    private fun providerRegistry(): ProviderRegistry = ProviderRegistry(
        listOf(LocalMediaProviderFactory(appContext.contentResolver), SubsonicProviderFactory()),
    )

    private fun row(
        kind: String,
        api: String = "local",
        host: String? = null,
        port: Int? = null,
    ): ProviderEntity = ProviderEntity(
        id = 7L,
        name = "row",
        api = api,
        kind = kind,
        primaryHost = host,
        primaryPort = port,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private suspend fun withDatabase(block: suspend (OmnisonicDatabase) -> Unit) {
        val db = OmnisonicDatabaseFactory(appContext).openInMemory()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun `device row loads the real local provider end to end from persisted storage`() = runTest {
        withDatabase { db ->
            db.providerDao().upsert(row("DEVICE"))
            val registry = ProviderClientRegistry(db, providerRegistry(), mapper)

            val provider = registry.providerFor(7L)

            assertThat(provider).isInstanceOf(LocalMediaProvider::class.java)
            assertThat(provider!!.streamUrl("501")).isEqualTo(
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 501).toString(),
            )
            assertThat(provider.listRadios()).isEmpty()
        }
    }

    @Test
    fun `virtual row loads the real local provider end to end from persisted storage`() = runTest {
        withDatabase { db ->
            db.providerDao().upsert(row("VIRTUAL"))
            val registry = ProviderClientRegistry(db, providerRegistry(), mapper)

            val provider = registry.providerFor(7L)

            assertThat(provider).isInstanceOf(LocalMediaProvider::class.java)
            assertThat(provider!!.coverArtUrl("ca-1")).isNull()
        }
    }

    @Test
    fun `device row resolves the local trait profile`() = runTest {
        withDatabase { db ->
            db.providerDao().upsert(row("DEVICE"))
            val loaded = db.providerDao().selectById(7L)!!

            assertThat((mapper.from(loaded) as ProviderLoadResult.Loaded).connection.endpoint).isEqualTo(
                app.omnisonic.provider.api.ProviderEndpoint.None,
            )
            assertThat(providerRegistry().traitsFor(loaded.api)).isEqualTo(
                ProviderTraits(
                    hasLibrarySync = true,
                    supportsDownload = false,
                    supportsServerTranscode = false,
                    reportsUserData = false,
                ),
            )
        }
    }

    @Test
    fun `remote row with a blank host stays unloadable through the mapper and registry`() = runTest {
        withDatabase { db ->
            db.providerDao().upsert(row("REMOTE_SERVER", api = "subsonic", host = " ", port = 4_433))

            assertTrue(mapper.from(db.providerDao().selectById(7L)!!) is ProviderLoadResult.Unloadable)
            val registry = ProviderClientRegistry(db, providerRegistry(), mapper)
            assertThat(registry.providerFor(7L)).isNull()
        }
    }
}
