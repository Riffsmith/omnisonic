package app.omnisonic.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * One typed settings key: the stored [Preferences.Key] plus the documented
 * [default] an absent key emits.
 */
data class SettingsKey<T> internal constructor(
    val name: String,
    val key: Preferences.Key<out T>,
    val default: T,
) {
    companion object {
        fun int(name: String, default: Int): SettingsKey<Int> =
            SettingsKey(name, intPreferencesKey(name), default)

        fun long(name: String, default: Long): SettingsKey<Long> =
            SettingsKey(name, longPreferencesKey(name), default)

        fun boolean(name: String, default: Boolean): SettingsKey<Boolean> =
            SettingsKey(name, booleanPreferencesKey(name), default)

        fun string(name: String, default: String): SettingsKey<String> =
            SettingsKey(name, stringPreferencesKey(name), default)

        fun stringOrNull(name: String): SettingsKey<String?> =
            SettingsKey(name, stringPreferencesKey(name), null)

        fun stringSet(name: String, default: Set<String> = emptySet()): SettingsKey<Set<String>> =
            SettingsKey(name, stringSetPreferencesKey(name), default)
    }
}

/**
 * Per-key reactive reads over the existing settings DataStores (shell, search,
 * history, download, transcode) — `docs/omnisonic/style/settings-facade.md` (D-74).
 *
 * [flow] is one typed flow per key: writing a key re-emits only that key's
 * flow, an absent key emits its [SettingsKey.default], and a DataStore read
 * failure falls back to `emptyPreferences` exactly like the owning stores.
 * Every key flow over one store derives from a single shared collection of
 * that store's `data` (started once, kept for the facade's lifetime), and the
 * facade never creates a DataStore itself — the one instance per file is
 * passed in, so DataStore's single-instance-per-file rule holds. Style values
 * never pass through here; they go through
 * `app.omnisonic.core.data.style.StyleProfileStore`.
 */
class SettingsFacade(
    private val scope: CoroutineScope,
) {

    private val storeFlows = ConcurrentHashMap<DataStore<Preferences>, Flow<Preferences>>()
    private val keyFlows = ConcurrentHashMap<Pair<DataStore<Preferences>, SettingsKey<*>>, Flow<*>>()

    /** The one shared flow for [key] over [store]; repeated calls return it unchanged. */
    fun <T> flow(store: DataStore<Preferences>, key: SettingsKey<T>): Flow<T> =
        @Suppress("UNCHECKED_CAST")
        keyFlows.computeIfAbsent(store to key) {
            shared(store)
                .map { prefs -> prefs[key.key] ?: key.default }
                .distinctUntilChanged()
        } as Flow<T>

    suspend fun <T> current(store: DataStore<Preferences>, key: SettingsKey<T>): T =
        store.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[key.key] ?: key.default }
            .first()

    private fun shared(store: DataStore<Preferences>): Flow<Preferences> =
        storeFlows.computeIfAbsent(store) {
            store.data
                .catch { emit(emptyPreferences()) }
                .shareIn(scope, SharingStarted.Eagerly, replay = 1)
        }

    companion object {
        fun create(scope: CoroutineScope): SettingsFacade = SettingsFacade(scope)
    }
}
