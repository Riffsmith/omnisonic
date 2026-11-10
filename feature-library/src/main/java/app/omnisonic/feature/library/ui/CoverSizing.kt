package app.omnisonic.feature.library.ui

/**
 * Derives the provider cover request size from the measured cell (D-17).
 * Original-size art costs 0.3–5 MB per image, which stalls a flinging grid;
 * the request is bucketed so neighbouring cells and rows share one cache entry.
 */
object CoverSizing {

    const val BUCKET_PX = 64
    const val MAX_SIZE_PX = 1024

    /** Null when the cell has no measurable dimension yet. */
    fun sizePx(widthPx: Int, heightPx: Int): Int? {
        val longest = maxOf(widthPx, heightPx)
        if (longest <= 0) return null
        val bucketed = (longest.toLong() + BUCKET_PX - 1) / BUCKET_PX * BUCKET_PX
        return bucketed.coerceAtMost(MAX_SIZE_PX.toLong()).toInt()
    }
}
