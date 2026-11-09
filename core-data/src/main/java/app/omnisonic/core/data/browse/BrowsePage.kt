package app.omnisonic.core.data.browse

/**
 * One hand-rolled LIMIT/OFFSET page (no Paging 3 — K7). `hasMore` is decided
 * by fetching one row past the window, so the caller never needs a second
 * query just to learn whether more content exists.
 */
data class BrowsePage<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
) {
    companion object {
        fun <T> empty(offset: Int, limit: Int): BrowsePage<T> =
            BrowsePage(items = emptyList(), offset = offset, limit = limit, hasMore = false)
    }
}
