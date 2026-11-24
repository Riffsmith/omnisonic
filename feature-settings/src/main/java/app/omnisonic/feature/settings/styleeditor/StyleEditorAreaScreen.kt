package app.omnisonic.feature.settings.styleeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * One area sub-screen of the nested editor stack (D-114 Q1 back chain:
 * Settings → hub → area). Areas whose controls land with their owning child
 * show a labelled placeholder instead of a control that does nothing; the
 * areas with a real screen carry the D-100/D-109 preview action. Styles has
 * its real screen since TODO 5b WP02 (`style-editor.md` §6).
 */
@Composable
fun StyleEditorAreaRoute(
    area: StyleEditorArea,
    onBack: () -> Unit,
    stylesState: StyleEditorStylesState = StyleEditorStylesState(),
    onPickStyle: (String) -> Unit = {},
    onDuplicateStyle: (String) -> Unit = {},
    onBeginRenameStyle: (String) -> Unit = {},
    onRenameDraftChange: (String) -> Unit = {},
    onConfirmRename: () -> Unit = {},
    onCancelRename: () -> Unit = {},
    onBeginDeleteStyle: (String) -> Unit = {},
    onResetDamagedStyle: (String) -> Unit = {},
    onExportRawStyle: (String) -> Unit = {},
    onDismissRawExport: () -> Unit = {},
    onConfirmLifecycle: () -> Unit = {},
    onCancelConfirm: () -> Unit = {},
    onPreviewHome: () -> Unit = {},
    onPreviewLibrary: () -> Unit = {},
    onPreviewNowPlaying: () -> Unit = {},
) {
    StyleEditorAreaScreen(
        area = area,
        onBack = onBack,
        stylesState = stylesState,
        onPickStyle = onPickStyle,
        onDuplicateStyle = onDuplicateStyle,
        onBeginRenameStyle = onBeginRenameStyle,
        onRenameDraftChange = onRenameDraftChange,
        onConfirmRename = onConfirmRename,
        onCancelRename = onCancelRename,
        onBeginDeleteStyle = onBeginDeleteStyle,
        onResetDamagedStyle = onResetDamagedStyle,
        onExportRawStyle = onExportRawStyle,
        onDismissRawExport = onDismissRawExport,
        onConfirmLifecycle = onConfirmLifecycle,
        onCancelConfirm = onCancelConfirm,
        onPreviewHome = onPreviewHome,
        onPreviewLibrary = onPreviewLibrary,
        onPreviewNowPlaying = onPreviewNowPlaying,
    )
}

@Composable
fun StyleEditorAreaScreen(
    area: StyleEditorArea,
    onBack: () -> Unit,
    stylesState: StyleEditorStylesState = StyleEditorStylesState(),
    onPickStyle: (String) -> Unit = {},
    onDuplicateStyle: (String) -> Unit = {},
    onBeginRenameStyle: (String) -> Unit = {},
    onRenameDraftChange: (String) -> Unit = {},
    onConfirmRename: () -> Unit = {},
    onCancelRename: () -> Unit = {},
    onBeginDeleteStyle: (String) -> Unit = {},
    onResetDamagedStyle: (String) -> Unit = {},
    onExportRawStyle: (String) -> Unit = {},
    onDismissRawExport: () -> Unit = {},
    onConfirmLifecycle: () -> Unit = {},
    onCancelConfirm: () -> Unit = {},
    onPreviewHome: () -> Unit = {},
    onPreviewLibrary: () -> Unit = {},
    onPreviewNowPlaying: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (area == StyleEditorArea.STYLES) {
        StyleEditorStylesScreen(
            state = stylesState,
            onPick = onPickStyle,
            onDuplicate = onDuplicateStyle,
            onBeginRename = onBeginRenameStyle,
            onRenameDraftChange = onRenameDraftChange,
            onConfirmRename = onConfirmRename,
            onCancelRename = onCancelRename,
            onBeginDelete = onBeginDeleteStyle,
            onResetDamaged = onResetDamagedStyle,
            onExportRaw = onExportRawStyle,
            onDismissRawExport = onDismissRawExport,
            onConfirmLifecycle = onConfirmLifecycle,
            onCancelConfirm = onCancelConfirm,
            onBack = onBack,
            modifier = modifier,
        )
        return
    }
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
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
                text = area.label(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        when (area) {
            // Styles renders its own full screen and returns above (D-116 Q9).
            StyleEditorArea.STYLES -> Unit

            StyleEditorArea.HOME -> TextButton(onClick = onPreviewHome, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.style_editor_preview_home))
            }

            StyleEditorArea.LIBRARY -> TextButton(onClick = onPreviewLibrary, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.style_editor_preview_library))
            }

            StyleEditorArea.NOW_PLAYING -> TextButton(onClick = onPreviewNowPlaying, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.style_editor_preview_now_playing))
            }

            StyleEditorArea.APPEARANCE,
            StyleEditorArea.IMPORT_EXPORT,
            StyleEditorArea.ADVANCED,
            -> Text(
                text = stringResource(R.string.style_editor_area_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
    }
}
