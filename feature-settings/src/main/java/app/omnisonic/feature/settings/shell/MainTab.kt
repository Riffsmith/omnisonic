package app.omnisonic.feature.settings.shell

/**
 * Main shell tabs. [id] equals the navigation route it opens, so the persisted
 * tab list doubles as the tab→route mapping (route table: reference/docs/UI_SCREENS.md §5 —
 * ids extend only, never rename).
 */
enum class MainTab(val id: String) {
    HOME("home"),
    LIBRARY("library"),
    SEARCH("search"),
    SETTINGS("settings"),
    ;

    companion object {
        val DEFAULT = listOf(HOME, LIBRARY, SEARCH, SETTINGS)

        fun fromId(id: String): MainTab? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Parses and sanitises the persisted tab list. Unknown ids, duplicates and
 * blanks are dropped (first occurrence wins); an empty result falls back to
 * [MainTab.DEFAULT], so the shell always has at least one reachable tab.
 */
object MainTabSet {

    fun parse(raw: String?): List<MainTab> {
        if (raw.isNullOrBlank()) return MainTab.DEFAULT
        val parsed = raw.split(',')
            .mapNotNull { token -> MainTab.fromId(token.trim()) }
            .distinct()
        return parsed.ifEmpty { MainTab.DEFAULT }
    }

    fun serialize(tabs: Collection<MainTab>): String =
        tabs.distinct().joinToString(separator = ",") { it.id }
}
