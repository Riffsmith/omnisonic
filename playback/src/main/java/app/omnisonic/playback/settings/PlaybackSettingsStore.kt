package app.omnisonic.playback.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.omnisonic.playback.api.player.OffloadMode
import app.omnisonic.playback.api.rg.ReplayGainMode
import app.omnisonic.playback.api.rg.ReplayGainSelector
import app.omnisonic.playback.api.rg.RgNormalisation
import app.omnisonic.playback.api.settings.DefaultNotificationActions
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.settings.PlaybackSettings
import app.omnisonic.playback.api.settings.TransientLossAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File

object PlaybackPrefs {
    val OFFLOAD = intPreferencesKey("engine.offload_mode")
    val RG_MODE = intPreferencesKey("engine.replay_gain_mode")
    val RG_NORM = intPreferencesKey("engine.replay_gain_normalisation")
    val RG_TARGET_LUFS = doublePreferencesKey("engine.replay_gain_target_lufs")
    val PREAMP_DB = floatPreferencesKey("engine.preamp_db")
    val MONO = booleanPreferencesKey("engine.mono")
    val CACHE_FIRST = booleanPreferencesKey("engine.cache_first")
    val CACHE_SIZE_BYTES = longPreferencesKey("engine.cache_size_bytes")
    val TRANSIENT_LOSS = intPreferencesKey("engine.transient_loss_action")
    val OFFLOAD_NAME = stringPreferencesKey("engine.offload_mode_name")
    val RG_MODE_NAME = stringPreferencesKey("engine.replay_gain_mode_name")
    val RG_NORM_NAME = stringPreferencesKey("engine.replay_gain_normalisation_name")
    val TRANSIENT_LOSS_NAME = stringPreferencesKey("engine.transient_loss_action_name")
    val NOTIF_SLOT_1 = stringPreferencesKey("engine.notification_action_1")
    val NOTIF_SLOT_2 = stringPreferencesKey("engine.notification_action_2")
    val NOTIF_SLOT_3 = stringPreferencesKey("engine.notification_action_3")
    val NOTIF_SLOT_4 = stringPreferencesKey("engine.notification_action_4")
    val NOTIF_SLOT_5 = stringPreferencesKey("engine.notification_action_5")
    val SHORT_FADE_MS = longPreferencesKey("engine.short_fade_ms")
}

class PlaybackSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : PlaybackSettings {
    override val offloadMode: Flow<OffloadMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            readEnum(prefs, PlaybackPrefs.OFFLOAD_NAME, PlaybackPrefs.OFFLOAD, OffloadMode.DISABLED)
        }

    override val replayGainMode: Flow<ReplayGainMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            readEnum(prefs, PlaybackPrefs.RG_MODE_NAME, PlaybackPrefs.RG_MODE, ReplayGainMode.OFF)
        }

    override val replayGainNormalisation: Flow<RgNormalisation> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            readEnum(prefs, PlaybackPrefs.RG_NORM_NAME, PlaybackPrefs.RG_NORM, RgNormalisation.RG2)
        }

    override val replayGainTargetLoudnessLufs: Flow<Double> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[PlaybackPrefs.RG_TARGET_LUFS] ?: ReplayGainSelector.DEFAULT_TARGET_LOUDNESS_LUFS }

    override val preampDb: Flow<Float> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[PlaybackPrefs.PREAMP_DB] ?: 0f }

    override val mono: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[PlaybackPrefs.MONO] ?: false }

    override val cacheFirst: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[PlaybackPrefs.CACHE_FIRST] ?: false }

    override val cacheSizeBytes: Flow<Long> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[PlaybackPrefs.CACHE_SIZE_BYTES] ?: DEFAULT_CACHE_SIZE_BYTES }

    override val transientLossAction: Flow<TransientLossAction> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            readEnum(prefs, PlaybackPrefs.TRANSIENT_LOSS_NAME, PlaybackPrefs.TRANSIENT_LOSS, TransientLossAction.DUCK)
        }

    override val notificationActions: Flow<List<NotificationAction>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            listOf(
                PlaybackPrefs.NOTIF_SLOT_1,
                PlaybackPrefs.NOTIF_SLOT_2,
                PlaybackPrefs.NOTIF_SLOT_3,
                PlaybackPrefs.NOTIF_SLOT_4,
                PlaybackPrefs.NOTIF_SLOT_5,
            ).mapIndexed { index, key ->
                prefs[key]?.let { name -> NotificationAction.entries.firstOrNull { it.name == name } }
                    ?: DefaultNotificationActions.SLOT_DEFAULTS[index]
            }
        }

    override val shortFadeMs: Flow<Long> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[PlaybackPrefs.SHORT_FADE_MS] ?: DEFAULT_SHORT_FADE_MS }

    suspend fun setOffloadMode(value: OffloadMode) = writeEnum(PlaybackPrefs.OFFLOAD_NAME, PlaybackPrefs.OFFLOAD, value)
    suspend fun setReplayGainMode(value: ReplayGainMode) = writeEnum(PlaybackPrefs.RG_MODE_NAME, PlaybackPrefs.RG_MODE, value)
    suspend fun setReplayGainNormalisation(value: RgNormalisation) = writeEnum(PlaybackPrefs.RG_NORM_NAME, PlaybackPrefs.RG_NORM, value)
    suspend fun setReplayGainTargetLoudnessLufs(value: Double) = edit { it[PlaybackPrefs.RG_TARGET_LUFS] = value }
    suspend fun setPreampDb(value: Float) = edit { it[PlaybackPrefs.PREAMP_DB] = value }
    suspend fun setMono(value: Boolean) = edit { it[PlaybackPrefs.MONO] = value }
    suspend fun setCacheFirst(value: Boolean) = edit { it[PlaybackPrefs.CACHE_FIRST] = value }
    suspend fun setCacheSizeBytes(value: Long) = edit { it[PlaybackPrefs.CACHE_SIZE_BYTES] = value }
    suspend fun setTransientLossAction(value: TransientLossAction) = writeEnum(PlaybackPrefs.TRANSIENT_LOSS_NAME, PlaybackPrefs.TRANSIENT_LOSS, value)
    suspend fun setNotificationActions(slots: List<NotificationAction>) {
        require(slots.size == 5)
        edit { prefs ->
            slots.forEachIndexed { index, action ->
                val key = when (index) {
                    0 -> PlaybackPrefs.NOTIF_SLOT_1
                    1 -> PlaybackPrefs.NOTIF_SLOT_2
                    2 -> PlaybackPrefs.NOTIF_SLOT_3
                    3 -> PlaybackPrefs.NOTIF_SLOT_4
                    else -> PlaybackPrefs.NOTIF_SLOT_5
                }
                prefs[key] = action.name
            }
        }
    }

    suspend fun setShortFadeMs(value: Long) = edit { it[PlaybackPrefs.SHORT_FADE_MS] = value }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    /**
     * Enums persist by stable NAME so reordering or inserting constants never
     * remaps a saved setting. The legacy `*_mode` int keys (ordinal storage)
     * are read for one release: an in-range ordinal maps back to its entry and
     * the next write clears the key; an out-of-range or unknown value falls
     * back to the default.
     */
    private inline fun <reified E : Enum<E>> readEnum(
        prefs: Preferences,
        nameKey: Preferences.Key<String>,
        legacyOrdinalKey: Preferences.Key<Int>,
        default: E,
    ): E {
        prefs[nameKey]?.let { name ->
            return enumValues<E>().firstOrNull { it.name == name } ?: default
        }
        val legacyOrdinal = prefs[legacyOrdinalKey] ?: return default
        return enumValues<E>().getOrNull(legacyOrdinal) ?: default
    }

    private suspend fun <E : Enum<E>> writeEnum(
        nameKey: Preferences.Key<String>,
        legacyOrdinalKey: Preferences.Key<Int>,
        value: E,
    ) {
        edit { prefs ->
            prefs[nameKey] = value.name
            prefs.remove(legacyOrdinalKey)
        }
    }

    companion object {
        const val DEFAULT_CACHE_SIZE_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_SHORT_FADE_MS = 120L

        fun create(context: Context): PlaybackSettingsStore {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("omnisonic_playback") },
            )
            return PlaybackSettingsStore(store)
        }

        fun createAt(file: File): PlaybackSettingsStore = PlaybackSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
        )
    }
}
