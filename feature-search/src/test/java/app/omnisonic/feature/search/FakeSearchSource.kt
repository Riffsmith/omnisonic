package app.omnisonic.feature.search

import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.search.RecentPick
import app.omnisonic.core.data.search.RecentPickKind
import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.core.data.search.SearchPage
import app.omnisonic.core.data.search.SearchPreferences
import app.omnisonic.core.data.search.SearchResults
import app.omnisonic.feature.search.search.SearchSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeSearchSource(
    recentPicks: List<RecentPick> = emptyList(),
    categories: Set<SearchCategory> = SearchCategory.entries.toSet(),
    pageSize: Int = 2,
    var songTotal: Int = 5,
) : SearchSource {

    data class Call(
        val query: String,
        val offset: Int,
        val limit: Int,
        val categories: Set<SearchCategory>,
    )

    private val prefs = MutableStateFlow(
        SearchPreferences(
            enabledCategories = categories,
            recentPicks = recentPicks,
            pageSize = pageSize,
        ),
    )

    override val preferences: Flow<SearchPreferences> = prefs.asStateFlow()

    val calls = mutableListOf<Call>()
    val recordedPicks = mutableListOf<RecentPick>()

    /** Identities this fake treats as deleted catalog rows. */
    val missing = mutableSetOf<Pair<RecentPickKind, Long>>()

    var failure: Throwable? = null
    var appendFailure: Throwable? = null

    /** Song row ids the fake returns with a null provider external id. */
    var songsWithNullExternal: Set<Long> = emptySet()

    /** When set, offset-0 searches wait on it outside cancellation (stale-result tests). */
    var gate: CompletableDeferred<Unit>? = null

    fun currentPreferences(): SearchPreferences = prefs.value

    override suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        categories: Set<SearchCategory>,
    ): SearchResults {
        calls += Call(query, offset, limit, categories)
        if (offset == 0) {
            gate?.let { pending -> kotlinx.coroutines.withContext(NonCancellable) { pending.await() } }
            failure?.let { throw it }
        } else {
            appendFailure?.let { throw it }
        }
        return SearchResults(
            query = query,
            terms = query.split(" "),
            categories = categories,
            songs = if (SearchCategory.SONGS in categories) {
                window(offset, limit, songTotal) { index ->
                    SongEntity(
                        id = index.toLong() + 1,
                        providerId = 1,
                        externalId = if (index.toLong() + 1 in songsWithNullExternal) null else "e-$index",
                        title = "Song $index",
                        displayArtist = "Artist $index",
                        durationMs = 200_000L,
                        updatedAt = 0,
                    )
                }
            } else {
                SearchPage.empty(offset, limit)
            },
            albums = SearchPage.empty(offset, limit),
            artists = SearchPage.empty(offset, limit),
            genres = SearchPage.empty(offset, limit),
            playlists = SearchPage.empty(offset, limit),
        )
    }

    override suspend fun recordSongPick(song: SongEntity) = record(
        RecentPick(
            kind = RecentPickKind.SONG,
            id = song.id,
            providerId = song.providerId,
            externalId = song.externalId,
            title = song.title,
            subtitle = song.displayArtist,
            imagePath = song.imagePath,
            durationMs = song.durationMs,
        ),
    )

    override suspend fun recordAlbumPick(album: AlbumEntity) = record(
        RecentPick(
            kind = RecentPickKind.ALBUM,
            id = album.id,
            providerId = album.providerId,
            title = album.title,
            subtitle = album.displayArtist,
            imagePath = album.imagePath,
        ),
    )

    override suspend fun recordArtistPick(artist: ArtistEntity) = record(
        RecentPick(
            kind = RecentPickKind.ARTIST,
            id = artist.id,
            providerId = artist.providerId,
            title = artist.title,
            imagePath = artist.imagePath,
        ),
    )

    override suspend fun recordGenrePick(genre: GenreEntity) = record(
        RecentPick(RecentPickKind.GENRE, genre.id, genre.providerId, title = genre.title),
    )

    override suspend fun recordPlaylistPick(playlist: PlaylistEntity) = record(
        RecentPick(
            kind = RecentPickKind.PLAYLIST,
            id = playlist.id,
            providerId = playlist.providerId,
            title = playlist.title,
            imagePath = playlist.imagePath,
        ),
    )

    override suspend fun recordRecentPick(pick: RecentPick) = record(pick)

    override suspend fun removeRecentPick(kind: RecentPickKind, id: Long) {
        prefs.update { current ->
            current.copy(recentPicks = current.recentPicks.filterNot { it.kind == kind && it.id == id })
        }
    }

    override suspend fun clearRecentPicks() {
        prefs.update { it.copy(recentPicks = emptyList()) }
    }

    override suspend fun resolveRecentPick(pick: RecentPick): RecentPick? =
        pick.takeUnless { (it.kind to it.id) in missing }

    override suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean) {
        prefs.update { current ->
            current.copy(
                enabledCategories = if (enabled) {
                    current.enabledCategories + category
                } else {
                    current.enabledCategories - category
                },
            )
        }
    }

    private fun record(pick: RecentPick) {
        recordedPicks += pick
        prefs.update { current ->
            current.copy(
                recentPicks = (listOf(pick) + current.recentPicks.filterNot {
                    it.kind == pick.kind && it.id == pick.id
                }).take(10),
            )
        }
    }

    private fun <T> window(
        offset: Int,
        limit: Int,
        total: Int,
        make: (Int) -> T,
    ): SearchPage<T> {
        if (offset >= total) return SearchPage.empty(offset, limit)
        val end = minOf(offset + limit, total)
        return SearchPage(
            items = (offset until end).map(make),
            offset = offset,
            limit = limit,
            hasMore = end < total,
        )
    }
}
