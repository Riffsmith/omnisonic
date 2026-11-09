package app.omnisonic.feature.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.library.browse.LibraryBrowseViewModel
import app.omnisonic.feature.library.browse.LibrarySection
import app.omnisonic.provider.api.CoverImageKey

/**
 * Typed entry for one `library/{section}` destination: starts the section's
 * initial page load and renders the matching paged body. Detail navigation
 * callbacks stay route-table-free (`:feature-library` never references
 * `ShellRoutes`); `:app` wires them.
 */
@Composable
fun LibrarySectionRoute(
    section: LibrarySection,
    viewModel: LibraryBrowseViewModel,
    onOpenAlbum: (Long) -> Unit = {},
    onOpenArtist: (Long) -> Unit = {},
    onOpenGenre: (Long) -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(section) { viewModel.load(section) }
    when (section) {
        LibrarySection.ALBUMS -> {
            val state by viewModel.albums.collectAsStateWithLifecycle()
            LibrarySectionScreen(
                section = section,
                state = state,
                showGridPlaceholder = true,
                onRetry = { viewModel.load(section) },
                modifier = modifier,
            ) { content ->
                CoverGridBody(
                    content = content,
                    itemKey = { it.id },
                    cover = { album -> album.imagePath?.let { CoverImageKey(album.providerId, it) } },
                    title = { it.title },
                    subtitle = { it.displayArtist },
                    onClick = { onOpenAlbum(it.id) },
                    onLoadMore = { viewModel.loadMore(section) },
                )
            }
        }

        LibrarySection.ARTISTS -> {
            val state by viewModel.artists.collectAsStateWithLifecycle()
            LibrarySectionScreen(
                section = section,
                state = state,
                showGridPlaceholder = true,
                onRetry = { viewModel.load(section) },
                modifier = modifier,
            ) { content ->
                CoverGridBody(
                    content = content,
                    itemKey = { it.id },
                    cover = { artist -> artist.imagePath?.let { CoverImageKey(artist.providerId, it) } },
                    title = { it.title },
                    subtitle = null,
                    onClick = { onOpenArtist(it.id) },
                    onLoadMore = { viewModel.loadMore(section) },
                )
            }
        }

        LibrarySection.GENRES -> {
            val state by viewModel.genres.collectAsStateWithLifecycle()
            LibrarySectionScreen(
                section = section,
                state = state,
                showGridPlaceholder = true,
                onRetry = { viewModel.load(section) },
                modifier = modifier,
            ) { content ->
                CoverGridBody(
                    content = content,
                    itemKey = { it.id },
                    cover = null,
                    title = { it.title },
                    subtitle = null,
                    titleInsideTile = { true },
                    onClick = { onOpenGenre(it.id) },
                    onLoadMore = { viewModel.loadMore(section) },
                )
            }
        }

        LibrarySection.SONGS -> {
            val state by viewModel.songs.collectAsStateWithLifecycle()
            LibrarySectionScreen(
                section = section,
                state = state,
                showGridPlaceholder = false,
                onRetry = { viewModel.load(section) },
                modifier = modifier,
            ) { content ->
                SongListBody(
                    content = content,
                    onLoadMore = { viewModel.loadMore(section) },
                )
            }
        }

        LibrarySection.PLAYLISTS -> {
            val state by viewModel.playlists.collectAsStateWithLifecycle()
            LibrarySectionScreen(
                section = section,
                state = state,
                showGridPlaceholder = true,
                onRetry = { viewModel.load(section) },
                modifier = modifier,
            ) { content ->
                CoverGridBody(
                    content = content,
                    itemKey = { it.id },
                    cover = { playlist -> playlist.imagePath?.let { CoverImageKey(playlist.providerId, it) } },
                    title = { it.title },
                    subtitle = null,
                    onClick = { onOpenPlaylist(it.id) },
                    onLoadMore = { viewModel.loadMore(section) },
                )
            }
        }
    }
}
