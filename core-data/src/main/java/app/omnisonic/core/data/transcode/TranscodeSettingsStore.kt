package app.omnisonic.core.data.transcode

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranscodeSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    fun settingsFor(renderer: TranscodeRendererId): Flow<TranscodeSettings> = dataStore.data
        .map { prefs -> prefs.readSettings(renderer) }

    suspend fun current(renderer: TranscodeRendererId = TranscodeRendererId.Local): TranscodeSettings =
        settingsFor(renderer).first()

    suspend fun setSettings(renderer: TranscodeRendererId, settings: TranscodeSettings): Unit {
        dataStore.edit { prefs ->
            val keys = RendererKeys(renderer)
            prefs.setNullable(keys.wifiBitrate, settings.wifiBitrateKbps)
            prefs.setNullable(keys.cellularBitrate, settings.cellularBitrateKbps)
            prefs.setNullable(keys.downloadBitrate, settings.downloadBitrateKbps)
            prefs[keys.mandatory] = settings.mandatory
            prefs[keys.vpnAsCellular] = settings.vpnAsCellular
            prefs[keys.meteredAsCellular] = settings.meteredAsCellular
            prefs.setNullable(keys.preferredFormat, settings.preferredFormat)
        }
    }

    private fun Preferences.readSettings(renderer: TranscodeRendererId): TranscodeSettings {
        val keys = RendererKeys(renderer)
        return TranscodeSettings(
            wifiBitrateKbps = TranscodeSettingsValidation.persistedBitrate(this[keys.wifiBitrate]),
            cellularBitrateKbps = TranscodeSettingsValidation.persistedBitrate(this[keys.cellularBitrate]),
            downloadBitrateKbps = TranscodeSettingsValidation.persistedBitrate(this[keys.downloadBitrate]),
            mandatory = this[keys.mandatory] ?: false,
            vpnAsCellular = this[keys.vpnAsCellular] ?: true,
            meteredAsCellular = this[keys.meteredAsCellular] ?: true,
            preferredFormat = TranscodeSettingsValidation.persistedFormat(this[keys.preferredFormat]),
        )
    }

    private fun <T> MutablePreferences.setNullable(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private class RendererKeys(renderer: TranscodeRendererId) {
        private val prefix = "transcode.${renderer.value}"

        val wifiBitrate = intPreferencesKey("$prefix.wifi_bitrate_kbps")
        val cellularBitrate = intPreferencesKey("$prefix.cellular_bitrate_kbps")
        val downloadBitrate = intPreferencesKey("$prefix.download_bitrate_kbps")
        val mandatory = booleanPreferencesKey("$prefix.mandatory")
        val vpnAsCellular = booleanPreferencesKey("$prefix.vpn_as_cellular")
        val meteredAsCellular = booleanPreferencesKey("$prefix.metered_as_cellular")
        val preferredFormat = stringPreferencesKey("$prefix.preferred_format")
    }

    companion object {
        fun create(context: Context): TranscodeSettingsStore = TranscodeSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("omnisonic_transcode") },
            ),
        )

        fun createAt(file: File): TranscodeSettingsStore = TranscodeSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
        )

        fun inMemory(): TranscodeSettingsStore = TranscodeSettingsStore(InMemoryDataStore())
    }

    internal class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val updateMutex = Mutex()
        override val data: Flow<Preferences> = state.asStateFlow()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            updateMutex.withLock {
                val next = transform(state.value)
                state.value = next
                next
            }
    }
}
