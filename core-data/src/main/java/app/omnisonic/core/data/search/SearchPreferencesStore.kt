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
import app.omnisonic.core.data.json.OmnisonicJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Types
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class SearchPreferences(
    val enabledCategories: Set<SearchCategory>,
    val recentPicks: List<RecentPick>,
    val pageSize: Int,
)

/**
 * Search user preferences: category toggles, recent result picks and the
 * LIMIT/OFFSET page size. Search strategy (tokenising, ranking) deliberately
 * lives in the repository, and page layout lives in `:style`; this store holds
 * only user choices.
 */
class SearchPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<SearchPreferences> = flow {
        deleteLegacyRecentsOnce()
        emitAll(
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .map { prefs ->
                    SearchPreferences(
                        enabledCategories = readCategories(prefs),
                        recentPicks = decodePicks(prefs[Keys.RECENT_PICKS]),
                        pageSize = sanitizePageSize(prefs[Keys.PAGE_SIZE] ?: DEFAULT_PAGE_SIZE),
                    )
                },
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

    /** Newest first, deduplicated by `(kind, id)`, capped at [MAX_RECENT_PICKS]. */
    suspend fun recordRecentPick(pick: RecentPick) {
        dataStore.edit { prefs ->
            val current = decodePicks(prefs[Keys.RECENT_PICKS])
            val next = (listOf(pick) + current.filterNot { it.sameIdentity(pick) })
                .take(MAX_RECENT_PICKS)
            prefs[Keys.RECENT_PICKS] = encodePicks(next)
            prefs.remove(Keys.LEGACY_RECENT_SEARCHES)
        }
    }

    suspend fun removeRecentPick(kind: RecentPickKind, id: Long) {
        dataStore.edit { prefs ->
            val next = decodePicks(prefs[Keys.RECENT_PICKS])
                .filterNot { it.kind == kind && it.id == id }
            prefs[Keys.RECENT_PICKS] = encodePicks(next)
            prefs.remove(Keys.LEGACY_RECENT_SEARCHES)
        }
    }

    suspend fun clearRecentPicks() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.RECENT_PICKS)
            prefs.remove(Keys.LEGACY_RECENT_SEARCHES)
        }
    }

    suspend fun setPageSize(pageSize: Int) {
        dataStore.edit { it[Keys.PAGE_SIZE] = sanitizePageSize(pageSize) }
    }

    private fun readCategories(prefs: Preferences): Set<SearchCategory> {
        if (!prefs.contains(Keys.CATEGORIES)) return DEFAULT_CATEGORIES
        val stored = prefs[Keys.CATEGORIES].orEmpty()
        return SearchCategory.entries.filterTo(LinkedHashSet()) { it.name in stored }
    }

    private fun decodePicks(encoded: String?): List<RecentPick> {
        val rows = encoded?.let { runCatching { pickListAdapter.fromJson(it) }.getOrNull() } ?: return emptyList()
        return rows.mapNotNull { row ->
            val kind = row.kind?.let { raw ->
                RecentPickKind.entries.firstOrNull { it.name == raw }
            } ?: return@mapNotNull null
            RecentPick(
                kind = kind,
                id = row.id,
                providerId = row.providerId,
                externalId = row.externalId,
                title = row.title ?: return@mapNotNull null,
                subtitle = row.subtitle,
                imagePath = row.imagePath,
                durationMs = row.durationMs,
            )
        }
    }

    private fun encodePicks(picks: List<RecentPick>): String = pickListAdapter.toJson(
        picks.map { pick ->
            RecentPickDto(
                kind = pick.kind.name,
                id = pick.id,
                providerId = pick.providerId,
                externalId = pick.externalId,
                title = pick.title,
                subtitle = pick.subtitle,
                imagePath = pick.imagePath,
                durationMs = pick.durationMs,
            )
        },
    )

    /**
     * One-shot upgrade cleanup: the pre-pick build stored typed queries under
     * `search.recent_searches`. They are deleted, never decoded into picks.
     */
    private suspend fun deleteLegacyRecentsOnce() {
        if (!legacyRecentsDeleted.compareAndSet(false, true)) return
        runCatching { dataStore.edit { it.remove(Keys.LEGACY_RECENT_SEARCHES) } }
    }

    private fun RecentPick.sameIdentity(other: RecentPick): Boolean =
        kind == other.kind && id == other.id

    private val legacyRecentsDeleted = AtomicBoolean(false)

    private object Keys {
        val CATEGORIES = stringSetPreferencesKey("search.categories")
        val RECENT_PICKS = stringPreferencesKey("search.recent_picks")
        val LEGACY_RECENT_SEARCHES = stringPreferencesKey("search.recent_searches")
        val PAGE_SIZE = intPreferencesKey("search.page_size")
    }

    @JsonClass(generateAdapter = true)
    internal data class RecentPickDto(
        val kind: String? = null,
        val id: Long = 0,
        val providerId: Long = 0,
        val externalId: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val imagePath: String? = null,
        val durationMs: Long? = null,
    )

    companion object {
        val DEFAULT_CATEGORIES: Set<SearchCategory> = SearchCategory.entries.toSet()
        const val DEFAULT_PAGE_SIZE = 30
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 200
        const val MAX_RECENT_PICKS = 10

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

        private val pickListAdapter = OmnisonicJson.moshi.adapter<List<RecentPickDto>>(
            Types.newParameterizedType(List::class.java, RecentPickDto::class.java),
        )
    }
}
