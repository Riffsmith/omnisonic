package app.omnisonic.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.omnisonic.feature.settings.R
import app.omnisonic.feature.settings.shell.MainTab

@Composable
fun MainTab.label(): String = stringResource(
    when (this) {
        MainTab.HOME -> R.string.tab_home
        MainTab.LIBRARY -> R.string.tab_library
        MainTab.SEARCH -> R.string.tab_search
        MainTab.SETTINGS -> R.string.tab_settings
    },
)
