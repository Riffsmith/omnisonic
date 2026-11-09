package app.omnisonic.feature.library.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.omnisonic.feature.library.browse.LibrarySection
import app.omnisonic.style.OmnisonicTheme

/**
 * Library hub (UI_SPEC.md §3.4): one entry per section, fixed Phase 5 order,
 * navigates to `library/{section}`.
 */
@Composable
fun LibraryHubScreen(
    onOpenSection: (LibrarySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LazyVerticalGrid(
        columns = GridCells.Fixed(tokens.layout.gridColumnsFor(isLandscape)),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.layout.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.layout.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(tokens.layout.gridSpacing),
    ) {
        items(LibrarySection.DEFAULT_ORDER, key = { it.id }) { section ->
            SectionEntry(
                section = section,
                onClick = { onOpenSection(section) },
            )
        }
    }
}

@Composable
private fun SectionEntry(section: LibrarySection, onClick: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .aspectRatio(tokens.layout.hubEntryAspectRatio)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(tokens.spacing.md),
        ) {
            Icon(
                imageVector = section.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(tokens.imagery.uiIconSize),
            )
            Spacer(Modifier.height(tokens.spacing.sm))
            Text(
                text = stringResource(section.labelRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun LibrarySection.icon(): ImageVector = when (this) {
    LibrarySection.ALBUMS -> Icons.Filled.Album
    LibrarySection.ARTISTS -> Icons.Filled.Person
    LibrarySection.GENRES -> Icons.Filled.Category
    LibrarySection.SONGS -> Icons.Filled.MusicNote
    LibrarySection.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
}
