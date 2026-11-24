package app.omnisonic.feature.settings.styleeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * The editor's first entry (D-114 Q1): the active style with a small live
 * preview, then one row per area in [StyleEditorArea]'s stable order. Back
 * pops to Settings; the route-scoped [StyleEditorViewModel] keeps the session
 * across the nested area sub-screens.
 */
@Composable
fun StyleEditorHubRoute(
    viewModel: StyleEditorViewModel = hiltViewModel(),
    onOpenArea: (StyleEditorArea) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.hubState.collectAsStateWithLifecycle()
    StyleEditorHubScreen(
        state = state,
        onOpenArea = onOpenArea,
        onUndo = viewModel::undo,
        onBack = onBack,
    )
}

@Composable
fun StyleEditorHubScreen(
    state: StyleEditorHubState,
    onOpenArea: (StyleEditorArea) -> Unit,
    onUndo: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.provider_setup_back),
                )
            }
            Text(
                text = stringResource(R.string.settings_style_editor),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val preview = state.previewTokens
            val name = state.activeStyleName.orEmpty()
            val previewDescription = stringResource(R.string.style_editor_preview_a11y, name)
            Surface(
                shape = RoundedCornerShape(preview.shapes.cornerScale.medium),
                color = preview.colors.primary,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = previewDescription },
            ) {}
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.style_editor_active_style),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = name, style = MaterialTheme.typography.bodyLarge)
                if (state.undoAvailable) {
                    TextButton(onClick = onUndo) {
                        Text(stringResource(R.string.style_editor_applied_undo))
                    }
                }
            }
        }
        state.areas.forEach { area ->
            val label = area.label()
            val rowDescription = stringResource(R.string.style_editor_area_row_a11y, label)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = rowDescription
                    }
                    .clickable { onOpenArea(area) }
                    .padding(vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            }
        }
    }
}
