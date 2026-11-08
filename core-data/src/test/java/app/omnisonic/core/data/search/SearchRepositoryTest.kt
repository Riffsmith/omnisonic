package app.omnisonic.core.data.search

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.provider.api.ProviderStatus
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchRepositoryTest : DatabaseTestBase() {

    private val allCategories = SearchCategory.entries.toSet()

    private class FakeMonitor(private val state: NetworkState) : NetworkMonitor {
        override fun current(): NetworkState = state
    }

    private fun preferencesStore(
        categories: Set<SearchCategory> = allCategories,
    ): SearchPreferencesStore = mockk {
        every { preferences } returns flowOf(
            SearchPreferences(
                enabledCategories = categories,
                recentSearches = emptyList(),
                pageSize = SearchPreferencesStore.DEFAULT_PAGE_SIZE,
            ),
        )
    }

    private fun visibilityStore(hidden: Set<Long> = emptySet()): ProviderVisibilityStore = mockk {
        every { hiddenProviderIds } returns flowOf(hidden)
    }

    private fun tracker(connected: Boolean = true): ProviderStatusTracker =
        ProviderStatusTracker(db, FakeMonitor(NetworkState(connected = connected, wifi = connected)))

    private fun repository(
        preferences: SearchPreferencesStore = preferencesStore(),
        visibility: ProviderVisibilityStore = visibilityStore(),
        statuses: ProviderStatusTracker = tracker(),
    ): SearchRepository = SearchRepository(searchDao, preferences, visibility, statuses)

    private suspend fun seedSong(
        id: Long,
        providerId: Long = 1,
        title: String,
        latinTitle: String? = null,
        sortTitle: String? = null,
        displayArtist: String? = null,
        lastPlayed: Long? = null,
    ): SongEntity {
        val song = SongEntity(
            id = id,
            providerId = providerId,
            externalId = "song-$id",
            title = title,
            latinTitle = latinTitle,
            sortTitle = sortTitle,
            displayArtist = displayArtist,
            lastPlayed = lastPlayed,
            updatedAt = 1_000,
        )
        songDao.upsert(song)
        return song
    }

    private suspend fun seedAlbum(
        id: Long,
        providerId: Long = 1,
        title: String,
        discNamesJson: String? = null,
    ): AlbumEntity {
        val album = AlbumEntity(
            id = id,
            providerId = providerId,
            externalId = "album-$id",
            title = title,
            discNamesJson = discNamesJson,
            updatedAt = 1_000,
        )
        albumDao.upsert(album)
        return album
    }

    @Test
    fun accentedTitleIsFoundByUnaccentedQueryThroughTheRomanColumn() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Beyoncé", latinTitle = "Beyonce", sortTitle = "beyonce")
        seedSong(id = 2, title = "Beyblade", sortTitle = "beyblade")
        val repository = repository()

        val results = repository.search("beyonce", categories = allCategories)
        val accentedQuery = repository.search("Beyoncé", categories = allCategories)

        assertThat(results.songs.items.map { it.title }).containsExactly("Beyoncé")
        assertThat(accentedQuery.songs.items.map { it.title }).containsExactly("Beyoncé")
    }

    @Test
    fun latinQueryFindsCyrillicTitleThroughTheRomanColumn() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Чёрный кот", latinTitle = "Chernyi kot", sortTitle = "chernyi kot")
        val repository = repository()

        val oneToken = repository.search("cherny", categories = allCategories)
        val twoTokens = repository.search("cherny kot", categories = allCategories)
        val miss = repository.search("cherny sobaka", categories = allCategories)

        assertThat(oneToken.songs.items.map { it.title }).containsExactly("Чёрный кот")
        assertThat(twoTokens.songs.items.map { it.title }).containsExactly("Чёрный кот")
        assertThat(miss.songs.items).isEmpty()
    }

    @Test
    fun threeTokenQueriesNarrowResults() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Alpha Beta Gamma")
        seedSong(id = 2, title = "Alpha Beta Delta")
        seedSong(id = 3, title = "Alpha Epsilon")
        val repository = repository()

        val oneToken = repository.search("alpha", categories = allCategories)
        val twoTokens = repository.search("alpha beta", categories = allCategories)
        val threeTokens = repository.search("alpha beta gamma", categories = allCategories)

        assertThat(oneToken.songs.items).hasSize(3)
        assertThat(twoTokens.songs.items.map { it.title })
            .containsExactly("Alpha Beta Delta", "Alpha Beta Gamma")
            .inOrder()
        assertThat(threeTokens.songs.items.map { it.title }).containsExactly("Alpha Beta Gamma")
    }

    @Test
    fun songsRankNewestPlayedFirstWithUnplayedLast() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Track", lastPlayed = 100)
        seedSong(id = 2, title = "Track", lastPlayed = 300)
        seedSong(id = 3, title = "Track")
        seedSong(id = 4, title = "Track", lastPlayed = 200)
        val repository = repository()

        val results = repository.search("track", categories = allCategories)

        assertThat(results.songs.items.map { it.id }).containsExactly(2L, 4L, 1L, 3L).inOrder()
    }

    @Test
    fun pagingWindowsWalkTheResultSetWithoutDuplicates() = runDbTest {
        newProvider(id = 1)
        repeat(5) { index -> seedSong(id = index + 1L, title = "Page song", lastPlayed = index.toLong()) }
        val repository = repository()

        val first = repository.search("page", offset = 0, limit = 2, categories = allCategories)
        val last = repository.search("page", offset = 4, limit = 2, categories = allCategories)
        val past = repository.search("page", offset = 8, limit = 2, categories = allCategories)

        assertThat(first.songs.items.map { it.id }).containsExactly(5L, 4L).inOrder()
        assertThat(first.songs.hasMore).isTrue()
        assertThat(last.songs.items.map { it.id }).containsExactly(1L)
        assertThat(last.songs.hasMore).isFalse()
        assertThat(past.songs.items).isEmpty()
        assertThat(past.songs.hasMore).isFalse()
    }

    @Test
    fun disabledCategoriesReturnEmptyPages() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Alpha song")
        seedAlbum(id = 2, title = "Alpha album")
        val repository = repository()

        val albumsOnly = repository.search("alpha", categories = setOf(SearchCategory.ALBUMS))

        assertThat(albumsOnly.songs.items).isEmpty()
        assertThat(albumsOnly.albums.items.map { it.title }).containsExactly("Alpha album")
        assertThat(albumsOnly.categories).isEqualTo(setOf(SearchCategory.ALBUMS))
    }

    @Test
    fun categoryTogglesComeFromPreferencesWhenNotPassed() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Jazz number")
        genreDao.upsert(GenreEntity(id = 2, providerId = 1, externalId = "gen-2", title = "Jazz", updatedAt = 1_000))
        val repository = repository(preferences = preferencesStore(categories = setOf(SearchCategory.GENRES)))

        val results = repository.search("jazz")

        assertThat(results.categories).isEqualTo(setOf(SearchCategory.GENRES))
        assertThat(results.songs.items).isEmpty()
        assertThat(results.genres.items.map { it.title }).containsExactly("Jazz")
    }

    @Test
    fun blankQueriesReturnEmptyResultsWithoutQuerying() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Something")
        val repository = repository()

        val results = repository.search("   ", categories = allCategories)

        assertThat(results.isEmpty).isTrue()
        assertThat(results.terms).isEmpty()
        assertThat(results.songs.items).isEmpty()
    }

    @Test
    fun hiddenAndUnavailableProvidersAreExcludedThroughTheScopeSeam() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        newProvider(id = 3)
        seedSong(id = 1, providerId = 1, title = "Shared")
        seedSong(id = 2, providerId = 2, title = "Shared")
        seedSong(id = 3, providerId = 3, title = "Shared")
        val statuses = tracker()
        statuses.markAuthFailed(3)
        val repository = repository(visibility = visibilityStore(hidden = setOf(2)), statuses = statuses)

        val results = repository.search("shared", categories = allCategories)
        val furtherScoped = repository.search(
            "shared",
            categories = allCategories,
            scope = ProfileScope(excludedProviderIds = listOf(1)),
        )

        assertThat(results.songs.items.map { it.providerId }).containsExactly(1L)
        assertThat(furtherScoped.songs.items).isEmpty()
    }

    @Test
    fun callerScopeExclusionsApplyWithoutDynamicBlocks() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 2)
        seedSong(id = 1, providerId = 1, title = "Reachable")
        seedSong(id = 2, providerId = 2, title = "Reachable")
        val repository = repository()

        val results = repository.search(
            "reachable",
            categories = allCategories,
            scope = ProfileScope(excludedProviderIds = listOf(1)),
        )

        assertThat(results.songs.items.map { it.providerId }).containsExactly(2L)
    }

    @Test
    fun csvExclusionMatchesWholeProviderIdsOnly() = runDbTest {
        newProvider(id = 1)
        newProvider(id = 12)
        seedSong(id = 1, providerId = 1, title = "Boundary")
        seedSong(id = 2, providerId = 12, title = "Boundary")
        val repository = repository()

        val excludingOne = repository.search(
            "boundary",
            categories = allCategories,
            scope = ProfileScope(excludedProviderIds = listOf(1)),
        )
        val excludingTwelve = repository.search(
            "boundary",
            categories = allCategories,
            scope = ProfileScope(excludedProviderIds = listOf(12)),
        )

        assertThat(excludingOne.songs.items.map { it.providerId }).containsExactly(12L)
        assertThat(excludingTwelve.songs.items.map { it.providerId }).containsExactly(1L)
    }

    @Test
    fun albumsMatchDiscNames() = runDbTest {
        newProvider(id = 1)
        seedAlbum(id = 1, title = "Concert Collection", discNamesJson = "[\"Disc One\",\"Live Disc\"]")
        val repository = repository()

        val hit = repository.search("live", categories = setOf(SearchCategory.ALBUMS))
        val miss = repository.search("studio", categories = setOf(SearchCategory.ALBUMS))

        assertThat(hit.albums.items.map { it.title }).containsExactly("Concert Collection")
        assertThat(miss.albums.items).isEmpty()
    }

    @Test
    fun hiddenPlaylistsAreNotSearchable() = runDbTest {
        newProvider(id = 1)
        playlistDao.upsert(
            PlaylistEntity(id = 0, providerId = 1, externalId = "pl-1", title = "Road Trip", type = 0, updatedAt = 1_000),
        )
        playlistDao.upsert(
            PlaylistEntity(id = 0, providerId = 1, externalId = "pl-2", title = "Archive", type = 0, hidden = true, updatedAt = 1_000),
        )
        val repository = repository()

        val trips = repository.search("trip", categories = setOf(SearchCategory.PLAYLISTS))
        val archived = repository.search("archive", categories = setOf(SearchCategory.PLAYLISTS))

        assertThat(trips.playlists.items.map { it.title }).containsExactly("Road Trip")
        assertThat(archived.playlists.items).isEmpty()
    }

    @Test
    fun artistsAndGenresMatchTheirLatinAndSortColumns() = runDbTest {
        newProvider(id = 1)
        artistDao.upsert(
            ArtistEntity(id = 1, providerId = 1, externalId = "art-1", title = "Björk", latinTitle = "Bjork", sortTitle = "bjork", updatedAt = 1_000),
        )
        genreDao.upsert(GenreEntity(id = 2, providerId = 1, externalId = "gen-2", title = "Jazz", sortTitle = "jazz", updatedAt = 1_000))
        val repository = repository()

        val artists = repository.search("bjork", categories = setOf(SearchCategory.ARTISTS))
        val genres = repository.search("JAZZ", categories = setOf(SearchCategory.GENRES))

        assertThat(artists.artists.items.map { it.title }).containsExactly("Björk")
        assertThat(genres.genres.items.map { it.title }).containsExactly("Jazz")
    }

    @Test
    fun everyEnabledCategoryReportsHasMoreWhenThePageIsFull() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Duet one", displayArtist = "Duet artist")
        seedSong(id = 2, title = "Duet two", displayArtist = "Duet artist")
        seedAlbum(id = 3, title = "Duet album")
        val repository = repository()

        val results = repository.search("duet", limit = 1, categories = allCategories)

        assertThat(results.songs.items).hasSize(1)
        assertThat(results.songs.hasMore).isTrue()
        assertThat(results.albums.items).hasSize(1)
        assertThat(results.albums.hasMore).isFalse()
        assertThat(results.totalItems).isEqualTo(2)
    }

    @Test
    fun offlineNetworkExcludesEveryProvider() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Shared")
        val repository = repository(statuses = tracker(connected = false))

        val results = repository.search("shared", categories = allCategories)

        assertThat(results.songs.items).isEmpty()
    }

    @Test
    fun unknownStatusesDoNotExcludeProviders() = runDbTest {
        newProvider(id = 1)
        seedSong(id = 1, title = "Shared")
        val statuses = tracker()
        val repository = repository(statuses = statuses)

        assertThat(statuses.statuses.value).isEmpty()

        val results = repository.search("shared", categories = allCategories)
        assertThat(results.songs.items.map { it.providerId }).containsExactly(1L)
    }
}
