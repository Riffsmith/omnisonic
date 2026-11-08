package app.omnisonic.core.data.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.omnisonic.core.data.json.JsonText
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class SearchPreferences(
    val enabledCategories: Set<SearchCategory>,
    val recentSearches: List<String>,
    val pageSize: Int,
)

/**
 * Search user preferences: category toggles, recent searches and the
 * LIMIT/OFFSET page size. Search strategy (tokenising, ranking) deliberately
 * lives in the repository, and page layout lives in `:style`; this store holds
 * only user choices.
 */
class SearchPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<SearchPreferences> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            SearchPreferences(
                enabledCategories = readCategories(prefs),
                recentSearches = JsonText.decodeStringList(prefs[Keys.RECENT_SEARCHES]),
                pageSize = sanitizePageSize(prefs[Keys.PAGE_SIZE] ?: DEFAULT_PAGE_SIZE),
            )
        }

    suspend fun setCategories(categories: Collection<SearchCategory>) {
        val names = categories.toSortedSet().map { it.name }.toSet()
        dataStore.edit { it[Keys.CATEGORIES] = names }
    }

    suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = readCategories(prefs).toMutableSet()
            if (enabled) current += category else current -= category
            prefs[Keys.CATEGORIES] = current.map { it.name }.toSet()
        }
    }

    /** Newest first, case-insensitively deduplicated, capped at [MAX_RECENT_SEARCHES]. */
    suspend fun recordRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = JsonText.decodeStringList(prefs[Keys.RECENT_SEARCHES])
            val next = (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) })
                .take(MAX_RECENT_SEARCHES)
            prefs[Keys.RECENT_SEARCHES] = JsonText.encodeStringList(next)
        }
    }

    suspend fun removeRecentSearch(query: String) {
        dataStore.edit { prefs ->
            val current = JsonText.decodeStringList(prefs[Keys.RECENT_SEARCHES])
            val next = current.filterNot { it == query }
            prefs[Keys.RECENT_SEARCHES] = JsonText.encodeStringList(next)
        }
    }

    suspend fun clearRecentSearches() {
        dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }
    }

    suspend fun setPageSize(pageSize: Int) {
        dataStore.edit { it[Keys.PAGE_SIZE] = sanitizePageSize(pageSize) }
    }

    private fun readCategories(prefs: Preferences): Set<SearchCategory> {
        if (!prefs.contains(Keys.CATEGORIES)) return DEFAULT_CATEGORIES
        val stored = prefs[Keys.CATEGORIES].orEmpty()
        return SearchCategory.entries.filterTo(LinkedHashSet()) { it.name in stored }
    }

    private object Keys {
        val CATEGORIES = stringSetPreferencesKey("search.categories")
        val RECENT_SEARCHES = stringPreferencesKey("search.recent_searches")
        val PAGE_SIZE = intPreferencesKey("search.page_size")
    }

    companion object {
        val DEFAULT_CATEGORIES: Set<SearchCategory> = SearchCategory.entries.toSet()
        const val DEFAULT_PAGE_SIZE = 30
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 200
        const val MAX_RECENT_SEARCHES = 10

        fun sanitizePageSize(pageSize: Int): Int = pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)

        fun create(context: Context): SearchPreferencesStore = SearchPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("omnisonic_search") },
            ),
        )

        fun createAt(file: File): SearchPreferencesStore = SearchPreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            ),
        )
    }
}
