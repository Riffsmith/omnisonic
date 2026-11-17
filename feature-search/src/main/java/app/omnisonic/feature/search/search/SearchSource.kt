package app.omnisonic.feature.search.search

import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.search.RecentPick
import app.omnisonic.core.data.search.RecentPickKind
import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.core.data.search.SearchPreferences
import app.omnisonic.core.data.search.SearchPreferencesStore
import app.omnisonic.core.data.search.SearchRepository
import app.omnisonic.core.data.search.SearchResults
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * The search read surface the ViewModel consumes: the repository call plus the
 * user preference flows it has to show and edit. Decouples the ViewModel from
 * Room and DataStore so plain JVM tests can fake results and preferences.
 */
interface SearchSource {

    val preferences: Flow<SearchPreferences>

    suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        categories: Set<SearchCategory>,
    ): SearchResults

    /** Records a tapped result as a recents pick. */
    suspend fun recordSongPick(song: SongEntity)

    suspend fun recordAlbumPick(album: AlbumEntity)

    suspend fun recordArtistPick(artist: ArtistEntity)

    suspend fun recordGenrePick(genre: GenreEntity)

    suspend fun recordPlaylistPick(playlist: PlaylistEntity)

    /** Re-records an already-resolved pick (a recents tap moves it to the top). */
    suspend fun recordRecentPick(pick: RecentPick)

    suspend fun removeRecentPick(kind: RecentPickKind, id: Long)

    suspend fun clearRecentPicks()

    /** Re-reads the pick's catalog row; null when the row no longer exists. */
    suspend fun resolveRecentPick(pick: RecentPick): RecentPick?

    suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean)
}

class RepositorySearchSource @Inject constructor(
    private val repository: SearchRepository,
    private val preferencesStore: SearchPreferencesStore,
) : SearchSource {

    override val preferences: Flow<SearchPreferences> = preferencesStore.preferences

    override suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        categories: Set<SearchCategory>,
    ): SearchResults = repository.search(
        query = query,
        offset = offset,
        limit = limit,
        categories = categories,
    )

    override suspend fun recordSongPick(song: SongEntity) =
        preferencesStore.recordRecentPick(repository.recentPickFor(song))

    override suspend fun recordAlbumPick(album: AlbumEntity) =
        preferencesStore.recordRecentPick(repository.recentPickFor(album))

    override suspend fun recordArtistPick(artist: ArtistEntity) =
        preferencesStore.recordRecentPick(repository.recentPickFor(artist))

    override suspend fun recordGenrePick(genre: GenreEntity) =
        preferencesStore.recordRecentPick(repository.recentPickFor(genre))

    override suspend fun recordPlaylistPick(playlist: PlaylistEntity) =
        preferencesStore.recordRecentPick(repository.recentPickFor(playlist))

    override suspend fun recordRecentPick(pick: RecentPick) =
        preferencesStore.recordRecentPick(pick)

    override suspend fun removeRecentPick(kind: RecentPickKind, id: Long) =
        preferencesStore.removeRecentPick(kind, id)

    override suspend fun clearRecentPicks() = preferencesStore.clearRecentPicks()

    override suspend fun resolveRecentPick(pick: RecentPick): RecentPick? =
        repository.resolveRecentPick(pick)

    override suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean) =
        preferencesStore.setCategoryEnabled(category, enabled)
}

