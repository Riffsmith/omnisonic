package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.dao.AutomaticRuleChangeDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Which table invalidated; every source requests the same recompute. */
enum class AutomaticRuleChangeSource {
    RULE_ROWS,
    SONG_ROWS,
    ALBUM_ROWS,
    PLAYLIST_ENTRIES,
    QUEUE_ROWS,
    OFFLINE_ROWS,
}

/**
 * Merged level-triggered changes for the six tables automatic rules depend on
 * (task 8a). The existing library invalidation flow covers catalog pages, not
 * favorites/song edits, album rows, playlist entries, the rule table or
 * download/offline settlements, so these are observed directly. Sources are not
 * queued or replayed: a change only means "recompute now", and the reaction
 * coordinator coalesces bursts.
 */
class AutomaticRuleChangeObserver(private val dao: AutomaticRuleChangeDao) {

    fun observe(): Flow<AutomaticRuleChangeSource> = merge(
        dao.observeRuleRows().map { AutomaticRuleChangeSource.RULE_ROWS },
        dao.observeSongRows().map { AutomaticRuleChangeSource.SONG_ROWS },
        dao.observeAlbumRows().map { AutomaticRuleChangeSource.ALBUM_ROWS },
        dao.observePlaylistEntries().map { AutomaticRuleChangeSource.PLAYLIST_ENTRIES },
        dao.observeQueueRows().map { AutomaticRuleChangeSource.QUEUE_ROWS },
        dao.observeOfflineRows().map { AutomaticRuleChangeSource.OFFLINE_ROWS },
    )
}
