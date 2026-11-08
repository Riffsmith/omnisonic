package app.omnisonic.feature.settings.shell

import app.omnisonic.style.NavigationLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Ordering helpers for the shell tab list. Pure so the settings UI and tests
 * share one definition of "show/hide keeps a sane order".
 */
object TabOrdering {

    fun setVisible(tabs: List<MainTab>, tab: MainTab, visible: Boolean): List<MainTab> {
        if (visible) {
            if (tab in tabs) return tabs
            val insertAt = tabs.indexOfFirst { candidate ->
                MainTab.DEFAULT.indexOf(candidate) > MainTab.DEFAULT.indexOf(tab)
            }
            return if (insertAt == -1) tabs + tab else tabs.toMutableList().apply { add(insertAt, tab) }
        }
        return tabs - tab
    }

    fun move(tabs: List<MainTab>, tab: MainTab, offset: Int): List<MainTab> {
        val from = tabs.indexOf(tab)
        if (from == -1) return tabs
        val to = (from + offset).coerceIn(0, tabs.lastIndex)
        if (to == from) return tabs
        return tabs.toMutableList().apply {
            removeAt(from)
            add(to, tab)
        }
    }
}

@HiltViewModel
class ShellSettingsViewModel @Inject constructor(
    private val store: ShellPreferencesStore,
) : ViewModel() {

    val preferences: StateFlow<ShellPreferences?> = store.preferences
        .map<ShellPreferences, ShellPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setVisibleTabs(tabs: List<MainTab>) {
        viewModelScope.launch { store.setVisibleTabs(tabs) }
    }

    fun setNavOverride(isLandscape: Boolean, layout: NavigationLayout?) {
        viewModelScope.launch { store.setNavOverride(isLandscape, layout) }
    }

    fun setTabVisible(tabs: List<MainTab>, tab: MainTab, visible: Boolean) {
        setVisibleTabs(TabOrdering.setVisible(tabs, tab, visible))
    }

    fun moveTab(tabs: List<MainTab>, tab: MainTab, offset: Int) {
        setVisibleTabs(TabOrdering.move(tabs, tab, offset))
    }
}
