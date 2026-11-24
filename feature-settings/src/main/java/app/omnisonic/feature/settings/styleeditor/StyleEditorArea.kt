package app.omnisonic.feature.settings.styleeditor

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.omnisonic.feature.settings.R

/**
 * The hub's areas in their stable order (D-65, D-114 Q1). [id] is the
 * `styleEditor/{area}` route argument and the `styleEditorPreview/{area}`
 * argument for the areas with a real screen to preview on.
 */
enum class StyleEditorArea(
    val id: String,
    @StringRes val labelRes: Int,
) {
    STYLES("styles", R.string.style_editor_area_styles),
    APPEARANCE("appearance", R.string.style_editor_area_appearance),
    HOME("home", R.string.style_editor_area_home),
    LIBRARY("library", R.string.style_editor_area_library),
    NOW_PLAYING("nowPlaying", R.string.style_editor_area_now_playing),
    IMPORT_EXPORT("importExport", R.string.style_editor_area_import_export),
    ADVANCED("advanced", R.string.style_editor_area_advanced),
    ;

    companion object {
        fun fromId(id: String): StyleEditorArea? = entries.firstOrNull { it.id == id }
    }
}

@Composable
fun StyleEditorArea.label(): String = stringResource(labelRes)
