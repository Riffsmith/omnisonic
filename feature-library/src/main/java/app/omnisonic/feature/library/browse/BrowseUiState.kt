package app.omnisonic.feature.library.browse

/**
 * Section state triplet (UI_SPEC.md §1.4) plus the load-more surface:
 * [Loading] before the first page, [Content] with an `appending` marker while
 * a further page is in flight, [Empty], and [Error] when the initial load
 * failed (`load` acts as its retry).
 */
sealed interface BrowseUiState<out T> {

    data object Loading : BrowseUiState<Nothing>

    data class Content<T>(
        val items: List<T>,
        val totalCount: Int?,
        val hasMore: Boolean,
        val appending: Boolean = false,
        val appendFailed: Boolean = false,
    ) : BrowseUiState<T>

    data object Empty : BrowseUiState<Nothing>

    data object Error : BrowseUiState<Nothing>
}
