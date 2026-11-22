package app.omnisonic.core.data.transcode

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class TranscodeSettingsStoreTest {
    @TempDir
    lateinit var tempDir: File

    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newDataStore(file: File): DataStore<Preferences> {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = storeScope
        return PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
    }

    private fun newStore(file: File = File(tempDir, "transcode-${System.nanoTime()}.preferences_pb")) =
        TranscodeSettingsStore(newDataStore(file))

    @Test
    fun `fresh store yields local defaults`() = runTest {
        assertThat(newStore().current()).isEqualTo(TranscodeSettings())
    }

    @Test
    fun `every local setting round-trips`() = runTest {
        val store = newStore()
        val expected = TranscodeSettings(
            wifiBitrateKbps = 320,
            cellularBitrateKbps = 128,
            downloadBitrateKbps = 192,
            mandatory = true,
            preferredFormat = "opus",
            vpnAsCellular = false,
            meteredAsCellular = false,
        )

        store.setSettings(TranscodeRendererId.Local, expected)

        assertThat(store.current()).isEqualTo(expected)
        assertThat(store.settingsFor(TranscodeRendererId.Local).first()).isEqualTo(expected)
    }

    @Test
    fun `off removes explicit bitrate and format values without changing mandatory`() = runTest {
        val dataStore = newDataStore(File(tempDir, "off.preferences_pb"))
        val store = TranscodeSettingsStore(dataStore)
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(320, 128, 192, mandatory = true, preferredFormat = "mp3"),
        )

        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(mandatory = true),
        )

        val prefs = dataStore.data.first()
        assertThat(prefs[WIFI_BITRATE]).isNull()
        assertThat(prefs[CELLULAR_BITRATE]).isNull()
        assertThat(prefs[DOWNLOAD_BITRATE]).isNull()
        assertThat(prefs[PREFERRED_FORMAT]).isNull()
        assertThat(prefs[MANDATORY]).isTrue()
        assertThat(prefs[VPN_AS_CELLULAR]).isTrue()
        assertThat(prefs[METERED_AS_CELLULAR]).isTrue()
        assertThat(store.current()).isEqualTo(TranscodeSettings(mandatory = true))
    }

    @Test
    fun `vpn and metered classification toggles round-trip per renderer`() = runTest {
        val store = newStore()
        val otherRenderer = TranscodeRendererId("test-renderer")

        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(vpnAsCellular = false, meteredAsCellular = false),
        )
        store.setSettings(otherRenderer, TranscodeSettings(meteredAsCellular = false))

        assertThat(store.current())
            .isEqualTo(TranscodeSettings(vpnAsCellular = false, meteredAsCellular = false))
        assertThat(store.current(otherRenderer)).isEqualTo(TranscodeSettings(meteredAsCellular = false))
    }

    @Test
    fun `absent classification toggles decode to the confirmed as-cellular defaults`() = runTest {
        val dataStore = newDataStore(File(tempDir, "legacy.preferences_pb"))
        dataStore.edit { prefs ->
            prefs[WIFI_BITRATE] = 320
            prefs[MANDATORY] = true
        }
        val prefs = dataStore.data.first()
        assertThat(prefs[VPN_AS_CELLULAR]).isNull()
        assertThat(prefs[METERED_AS_CELLULAR]).isNull()

        assertThat(TranscodeSettingsStore(dataStore).current())
            .isEqualTo(TranscodeSettings(wifiBitrateKbps = 320, mandatory = true))
    }

    @Test
    fun `invalid persisted values decode independently to off or no preference`() = runTest {
        val dataStore = newDataStore(File(tempDir, "invalid.preferences_pb"))
        dataStore.edit { prefs ->
            prefs[WIFI_BITRATE] = 0
            prefs[CELLULAR_BITRATE] = 128
            prefs[DOWNLOAD_BITRATE] = 192
            prefs[MANDATORY] = true
            prefs[PREFERRED_FORMAT] = "MP3"
        }

        assertThat(TranscodeSettingsStore(dataStore).current())
            .isEqualTo(TranscodeSettings(cellularBitrateKbps = 128, downloadBitrateKbps = 192, mandatory = true))

        dataStore.edit { prefs ->
            prefs[WIFI_BITRATE] = 320
            prefs[CELLULAR_BITRATE] = -1
            prefs[PREFERRED_FORMAT] = "opus"
        }

        assertThat(TranscodeSettingsStore(dataStore).current()).isEqualTo(
            TranscodeSettings(
                wifiBitrateKbps = 320,
                downloadBitrateKbps = 192,
                mandatory = true,
                preferredFormat = "opus",
            ),
        )
    }

    @Test
    fun `positive persisted boundaries remain explicit values`() = runTest {
        val dataStore = newDataStore(File(tempDir, "boundaries.preferences_pb"))
        dataStore.edit { prefs ->
            prefs[WIFI_BITRATE] = 1
            prefs[CELLULAR_BITRATE] = Int.MAX_VALUE
            prefs[DOWNLOAD_BITRATE] = 192
            prefs[PREFERRED_FORMAT] = "flac"
        }

        assertThat(TranscodeSettingsStore(dataStore).current()).isEqualTo(
            TranscodeSettings(1, Int.MAX_VALUE, 192, preferredFormat = "flac"),
        )
    }

    @Test
    fun `renderer settings do not overwrite each other`() = runTest {
        val store = newStore()
        val otherRenderer = TranscodeRendererId("test-renderer")
        val local = TranscodeSettings(wifiBitrateKbps = 320, preferredFormat = "flac")
        val other = TranscodeSettings(
            wifiBitrateKbps = 96,
            cellularBitrateKbps = 64,
            downloadBitrateKbps = 192,
            mandatory = true,
            preferredFormat = "opus",
        )
        store.setSettings(TranscodeRendererId.Local, local)
        store.setSettings(otherRenderer, other)

        store.setSettings(TranscodeRendererId.Local, TranscodeSettings(downloadBitrateKbps = 256))

        assertThat(store.current()).isEqualTo(TranscodeSettings(downloadBitrateKbps = 256))
        assertThat(store.current(otherRenderer)).isEqualTo(other)
    }

    @Test
    fun `settings persist across store instances on one file`() = runTest {
        val file = File(tempDir, "shared.preferences_pb")
        val expected = TranscodeSettings(320, 128, 192, mandatory = true, preferredFormat = "aac")
        val writerJob = SupervisorJob()
        val writerScope = CoroutineScope(writerJob + Dispatchers.IO)
        val writer = TranscodeSettingsStore(
            PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file }),
        )
        writer.setSettings(TranscodeRendererId.Local, expected)
        writerScope.cancel()
        withTimeoutOrNull(2_000) { while (writerJob.children.any()) delay(10) }
        check(writerJob.isCompleted) { "writer scope never completed" }

        assertThat(newStore(file).current()).isEqualTo(expected)
    }

    @Test
    fun `in-memory store round-trips without a file`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        val expected = TranscodeSettings(cellularBitrateKbps = 96, preferredFormat = "mp3")

        store.setSettings(TranscodeRendererId.Local, expected)

        assertThat(store.current()).isEqualTo(expected)
    }

    @Test
    fun `in-memory updates serialize across suspension`() = runTest {
        val dataStore = TranscodeSettingsStore.InMemoryDataStore()
        val firstKey = intPreferencesKey("first")
        val secondKey = intPreferencesKey("second")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            dataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    this[firstKey] = 1
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            }
        }
        firstStarted.await()
        val second = async {
            dataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply { this[secondKey] = 2 }
            }
        }

        yield()
        assertThat(second.isCompleted).isFalse()
        releaseFirst.complete(Unit)
        awaitAll(first, second)
        val persisted = dataStore.data.first()
        assertThat(persisted[firstKey]).isEqualTo(1)
        assertThat(persisted[secondKey]).isEqualTo(2)
        Unit
    }

    private companion object {
        val WIFI_BITRATE = intPreferencesKey("transcode.local.wifi_bitrate_kbps")
        val CELLULAR_BITRATE = intPreferencesKey("transcode.local.cellular_bitrate_kbps")
        val DOWNLOAD_BITRATE = intPreferencesKey("transcode.local.download_bitrate_kbps")
        val MANDATORY = booleanPreferencesKey("transcode.local.mandatory")
        val VPN_AS_CELLULAR = booleanPreferencesKey("transcode.local.vpn_as_cellular")
        val METERED_AS_CELLULAR = booleanPreferencesKey("transcode.local.metered_as_cellular")
        val PREFERRED_FORMAT = stringPreferencesKey("transcode.local.preferred_format")
    }
}
