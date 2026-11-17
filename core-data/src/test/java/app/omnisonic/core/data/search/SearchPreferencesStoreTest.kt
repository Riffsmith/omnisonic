package app.omnisonic.core.data.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    private fun newDataStore(file: File): DataStore<Preferences> {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = storeScope
        return PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
    }

    private fun newStore(file: File = File(tempDir, "search-${System.nanoTime()}.preferences_pb")) =
        SearchPreferencesStore(newDataStore(file))

    private fun songPick(
        id: Long,
        title: String = "Song $id",
        imagePath: String? = "cover-$id.jpg",
    ) = RecentPick(
        kind = RecentPickKind.SONG,
        id = id,
        providerId = 1,
        externalId = "ext-$id",
        title = title,
        subtitle = "Artist $id",
        imagePath = imagePath,
        durationMs = 200_000L,
    )

    @Test
    fun `fresh store yields defaults`() = runTest {
        val store = newStore()

        val prefs = store.preferences.first()

        assertThat(prefs.enabledCategories).isEqualTo(SearchCategory.entries.toSet())
        assertThat(prefs.recentPicks).isEmpty()
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
    fun `picks round-trip artwork subtitle external id and duration`() = runTest {
        val store = newStore()
        val pick = songPick(id = 4, title = "Doomsday", imagePath = "dooms.jpg")

        store.recordRecentPick(pick)

        assertThat(store.preferences.first().recentPicks).containsExactly(pick)
    }

    @Test
    fun `picks keep the newest first and dedupe by kind and id`() = runTest {
        val store = newStore()

        store.recordRecentPick(songPick(id = 1, title = "Old title"))
        store.recordRecentPick(songPick(id = 2, title = "Night Drive"))
        store.recordRecentPick(songPick(id = 1, title = "New title"))

        val picks = store.preferences.first().recentPicks
        assertThat(picks.map { it.title }).containsExactly("New title", "Night Drive").inOrder()
    }

    @Test
    fun `picks of different kinds with the same row id are distinct`() = runTest {
        val store = newStore()

        store.recordRecentPick(songPick(id = 7))
        store.recordRecentPick(
            RecentPick(RecentPickKind.ALBUM, id = 7, providerId = 1, title = "Album 7"),
        )

        assertThat(store.preferences.first().recentPicks.map { it.kind })
            .containsExactly(RecentPickKind.ALBUM, RecentPickKind.SONG).inOrder()
    }

    @Test
    fun `picks are capped at the newest ten`() = runTest {
        val store = newStore()

        repeat(12) { index -> store.recordRecentPick(songPick(id = index.toLong())) }

        val picks = store.preferences.first().recentPicks
        assertThat(picks).hasSize(SearchPreferencesStore.MAX_RECENT_PICKS)
        assertThat(picks.first().id).isEqualTo(11L)
        assertThat(picks.last().id).isEqualTo(2L)
    }

    @Test
    fun `picks can be removed by identity or cleared`() = runTest {
        val store = newStore()
        store.recordRecentPick(songPick(id = 1))
        store.recordRecentPick(songPick(id = 2))

        store.removeRecentPick(RecentPickKind.SONG, 1)
        assertThat(store.preferences.first().recentPicks.map { it.id }).containsExactly(2L)

        store.clearRecentPicks()
        assertThat(store.preferences.first().recentPicks).isEmpty()
    }

    @Test
    fun `malformed pick json decodes to an empty list`() = runTest {
        val dataStore = newDataStore(File(tempDir, "malformed.preferences_pb"))
        dataStore.edit { it[PICKS_KEY] = "{not-json" }

        assertThat(SearchPreferencesStore(dataStore).preferences.first().recentPicks).isEmpty()
    }

    @Test
    fun `an unknown pick kind is skipped while valid picks survive`() = runTest {
        val dataStore = newDataStore(File(tempDir, "unknown-kind.preferences_pb"))
        dataStore.edit {
            it[PICKS_KEY] = """
                [
                  {"kind":"SONG","id":1,"providerId":1,"title":"Keep"},
                  {"kind":"PODCAST","id":2,"providerId":1,"title":"Future"},
                  {"kind":"ALBUM","id":3,"providerId":1,"title":"Also keep"}
                ]
            """.trimIndent()
        }

        val picks = SearchPreferencesStore(dataStore).preferences.first().recentPicks

        assertThat(picks.map { it.title }).containsExactly("Keep", "Also keep").inOrder()
    }

    @Test
    fun `legacy query recents are deleted on first access and never become picks`() = runTest {
        val dataStore = newDataStore(File(tempDir, "legacy.preferences_pb"))
        dataStore.edit { it[LEGACY_KEY] = "[\"dooms\",\"bowie\"]" }
        val store = SearchPreferencesStore(dataStore)

        assertThat(store.preferences.first().recentPicks).isEmpty()

        val raw = dataStore.data.first()
        assertThat(raw.contains(LEGACY_KEY)).isFalse()
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
        first.recordRecentPick(songPick(id = 9, title = "Night Drive"))
        first.setPageSize(50)
        firstScope.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }

        val second = newStore(file)
        val prefs = second.preferences.first()

        assertThat(prefs.enabledCategories).doesNotContain(SearchCategory.SONGS)
        assertThat(prefs.recentPicks.map { it.title }).containsExactly("Night Drive")
        assertThat(prefs.pageSize).isEqualTo(50)
    }

    private companion object {
        val PICKS_KEY = stringPreferencesKey("search.recent_picks")
        val LEGACY_KEY = stringPreferencesKey("search.recent_searches")
    }
}
