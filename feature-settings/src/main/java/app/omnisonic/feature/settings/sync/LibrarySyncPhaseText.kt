package app.omnisonic.feature.settings.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.omnisonic.core.data.sync.LibrarySyncPhase
import app.omnisonic.feature.settings.R

/** The walk order shown on the first-run screen; terminal phases are not steps. */
internal val LibrarySyncWalkPhases: List<LibrarySyncPhase> = listOf(
    LibrarySyncPhase.ARTISTS,
    LibrarySyncPhase.ALBUMS_SONGS,
    LibrarySyncPhase.GENRES,
    LibrarySyncPhase.RADIOS,
    LibrarySyncPhase.PLAYLISTS,
    LibrarySyncPhase.BOOKMARKS,
    LibrarySyncPhase.PRUNE,
)

@Composable
internal fun LibrarySyncPhase.label(): String = stringResource(
    when (this) {
        LibrarySyncPhase.ARTISTS -> R.string.library_sync_phase_artists
        LibrarySyncPhase.ALBUMS_SONGS -> R.string.library_sync_phase_albums_songs
        LibrarySyncPhase.GENRES -> R.string.library_sync_phase_genres
        LibrarySyncPhase.RADIOS -> R.string.library_sync_phase_radios
        LibrarySyncPhase.PLAYLISTS -> R.string.library_sync_phase_playlists
        LibrarySyncPhase.BOOKMARKS -> R.string.library_sync_phase_bookmarks
        LibrarySyncPhase.PRUNE -> R.string.library_sync_phase_prune
        LibrarySyncPhase.DONE -> R.string.library_sync_phase_done
        LibrarySyncPhase.FAILED -> R.string.library_sync_phase_failed
        LibrarySyncPhase.BLOCKED -> R.string.library_sync_phase_blocked
        LibrarySyncPhase.SKIPPED -> R.string.library_sync_phase_skipped
    },
)
