package app.omnisonic.core.data.search

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPreferencesStoreTest {

    @TempDir
    lateinit var tempDir: File

    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newStore(file: File = File(tempDir, "search-${System.nanoTime()}.preferences_pb")): SearchPreferencesStore {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = storeScope
        return SearchPreferencesStore(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }),
        )
    }

    @Test
    fun `fresh store yields defaults`() = runTest {
        val store = newStore()

        val prefs = store.preferences.first()

        assertThat(prefs.enabledCategories).isEqualTo(SearchCategory.entries.toSet())
        assertThat(prefs.recentSearches).isEmpty()
        assertThat(prefs.pageSize).isEqualTo(SearchPreferencesStore.DEFAULT_PAGE_SIZE)
    }

    @Test
    fun `category set replaces the whole toggle state`() = runTest {
        val store = newStore()

        store.setCategories(listOf(SearchCategory.SONGS, SearchCategory.PLAYLISTS))
        assertThat(store.preferences.first().enabledCategories)
            .containsExactly(SearchCategory.SONGS, SearchCategory.PLAYLISTS)

        store.setCategories(emptyList())
        assertThat(store.preferences.first().enabledCategories).isEmpty()
    }

    @Test
    fun `single category toggles flip one entry`() = runTest {
        val store = newStore()

        store.setCategoryEnabled(SearchCategory.SONGS, false)
        assertThat(store.preferences.first().enabledCategories).doesNotContain(SearchCategory.SONGS)

        store.setCategoryEnabled(SearchCategory.SONGS, true)
        assertThat(store.preferences.first().enabledCategories).contains(SearchCategory.SONGS)
    }

    @Test
    fun `recent searches keep the newest first and dedupe case-insensitively`() = runTest {
        val store = newStore()

        store.recordRecentSearch("  night drive  ")
        store.recordRecentSearch("Night Drive")
        store.recordRecentSearch("rain")

        assertThat(store.preferences.first().recentSearches).containsExactly("rain", "Night Drive").inOrder()
    }

    @Test
    fun `blank queries are not recorded`() = runTest {
        val store = newStore()

        store.recordRecentSearch("   ")

        assertThat(store.preferences.first().recentSearches).isEmpty()
    }

    @Test
    fun `recent searches are capped at the newest ten`() = runTest {
        val store = newStore()

        repeat(12) { index -> store.recordRecentSearch("query-$index") }

        val recents = store.preferences.first().recentSearches
        assertThat(recents).hasSize(SearchPreferencesStore.MAX_RECENT_SEARCHES)
        assertThat(recents.first()).isEqualTo("query-11")
        assertThat(recents.last()).isEqualTo("query-2")
    }

    @Test
    fun `recent searches can be removed one by one or cleared`() = runTest {
        val store = newStore()
        store.recordRecentSearch("keep")
        store.recordRecentSearch("drop")

        store.removeRecentSearch("drop")
        assertThat(store.preferences.first().recentSearches).containsExactly("keep")

        store.clearRecentSearches()
        assertThat(store.preferences.first().recentSearches).isEmpty()
    }

    @Test
    fun `page size is clamped into the supported range`() = runTest {
        val store = newStore()

        store.setPageSize(0)
        assertThat(store.preferences.first().pageSize).isEqualTo(SearchPreferencesStore.MIN_PAGE_SIZE)

        store.setPageSize(10_000)
        assertThat(store.preferences.first().pageSize).isEqualTo(SearchPreferencesStore.MAX_PAGE_SIZE)
    }

    @Test
    fun `preferences persist across store instances on one file`() = runTest {
        val file = File(tempDir, "shared.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = SearchPreferencesStore(
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
        )
        first.setCategoryEnabled(SearchCategory.SONGS, false)
        first.recordRecentSearch("night drive")
        first.setPageSize(50)
        firstScope.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }

        val second = newStore(file)
        val prefs = second.preferences.first()

        assertThat(prefs.enabledCategories).doesNotContain(SearchCategory.SONGS)
        assertThat(prefs.recentSearches).containsExactly("night drive")
        assertThat(prefs.pageSize).isEqualTo(50)
    }
}
