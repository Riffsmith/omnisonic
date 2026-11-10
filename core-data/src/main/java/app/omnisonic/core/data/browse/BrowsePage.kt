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

/**
 * Shared LIMIT/OFFSET page fetch (K7, no Paging 3): clamps the window, fetches
 * one row past it and derives [BrowsePage.hasMore] from the overflow — the
 * contract browse and detail repositories share.
 */
internal suspend fun <T> fetchPage(
    offset: Int,
    limit: Int,
    rows: suspend (fetchLimit: Int) -> List<T>,
): BrowsePage<T> {
    val safeOffset = offset.coerceAtLeast(0)
    val safeLimit = BrowseRepository.sanitizeLimit(limit)
    val fetched = rows(safeLimit + 1)
    return BrowsePage(
        items = fetched.take(safeLimit),
        offset = safeOffset,
        limit = safeLimit,
        hasMore = fetched.size > safeLimit,
    )
}
