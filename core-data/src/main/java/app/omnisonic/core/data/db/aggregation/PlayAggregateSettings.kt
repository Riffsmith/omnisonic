package app.omnisonic.core.data.db.aggregation

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Options for the album/artist recompute that runs inside a history write.
 *
 * Defaults follow the studied behaviour [INFERRED from `DATABASE_SCHEMA.md`
 * §4.3]: album play count is the rounded average of its songs, and artist play
 * count includes only track-level credits.
 * Persistence and the settings surface land with the history settings task;
 * this holder is the single read point for the write path either way.
 */
@Singleton
class PlayAggregateSettings @Inject constructor() {

    data class Options(
        val albumCountRule: AlbumCountRule = AlbumCountRule.AVERAGE,
        val includeSupportingArtists: Boolean = false,
    )

    @Volatile
    private var current = Options()

    fun options(): Options = current

    fun update(options: Options) {
        current = options
    }
}
