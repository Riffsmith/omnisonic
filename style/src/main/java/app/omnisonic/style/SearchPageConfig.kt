package app.omnisonic.style

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** How the search category toggles are laid out under the query field. */
enum class FilterChipLayout { SINGLE_LINE, WRAPPED }

/**
 * Search page layout options (UI_SPEC.md §3.6): the search screen reads this
 * config and never hard-codes the chip row or the empty state's sections.
 * Search strategy (tokenising, ranking, paging) stays in `core-data`; user
 * choices (enabled categories, recent searches) stay in the preferences
 * store. The JSON form is the Phase 9 style-file shape.
 */
data class SearchPageConfig(
    val chipLayout: FilterChipLayout = FilterChipLayout.SINGLE_LINE,
    val autofocusOnEntry: Boolean = true,
    val showRecentSearches: Boolean = true,
) {

    fun toJson(): String = adapter.toJson(this)

    companion object {

        const val DEFAULT_JSON =
            """{"chipLayout":"SINGLE_LINE","autofocusOnEntry":true,"showRecentSearches":true}"""

        val DEFAULT: SearchPageConfig = SearchPageConfig()

        private val adapter by lazy {
            Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
                .adapter(SearchPageConfig::class.java)
        }

        /**
         * Parses the layout JSON, falling back to [DEFAULT] for malformed
         * input, unknown values and missing fields (an absent key keeps its
         * default).
         */
        fun fromJson(json: String): SearchPageConfig = try {
            adapter.fromJson(json) ?: DEFAULT
        } catch (e: JsonDataException) {
            DEFAULT
        } catch (e: java.io.IOException) {
            DEFAULT
        }
    }
}
