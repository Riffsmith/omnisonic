package app.omnisonic.core.data.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Providers the user chose to hide from library and search surfaces. The
 * search repository merges this set into the [app.omnisonic.core.data.db.ProfileScope]
 * exclusion seam; a hidden-provider management UI is Phase 9/10 work.
 */
class ProviderVisibilityStore(
    private val dataStore: DataStore<Preferences>,
) {

    val hiddenProviderIds: Flow<Set<Long>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[Keys.HIDDEN_PROVIDER_IDS]
                .orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .toSet()
        }

    suspend fun setHidden(providerId: Long, hidden: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_PROVIDER_IDS].orEmpty().toMutableSet()
            if (hidden) current += providerId.toString() else current -= providerId.toString()
            prefs[Keys.HIDDEN_PROVIDER_IDS] = current
        }
    }

    suspend fun replaceHidden(providerIds: Collection<Long>) {
        dataStore.edit { it[Keys.HIDDEN_PROVIDER_IDS] = providerIds.mapTo(mutableSetOf()) { id -> id.toString() } }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(Keys.HIDDEN_PROVIDER_IDS) }
    }

    private object Keys {
        val HIDDEN_PROVIDER_IDS = stringSetPreferencesKey("providers.hidden_ids")
    }

    companion object {
        fun create(context: Context): ProviderVisibilityStore = ProviderVisibilityStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("omnisonic_provider_visibility") },
            ),
        )

        fun createAt(file: File): ProviderVisibilityStore = ProviderVisibilityStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
        )
    }
}
