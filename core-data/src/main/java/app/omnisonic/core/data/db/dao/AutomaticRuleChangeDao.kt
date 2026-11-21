package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Level-triggered change surfaces for automatic-rule reactions (task 8a).
 * Room re-runs each query on any invalidation of its table, so a count is only
 * a cheap emission token: rule variant edits, favorite/song/album edits,
 * playlist reorders and queue/offline settlements all surface even when the
 * count is unchanged. No schema object is added; these are read-only
 * observers.
 */
@Dao
interface AutomaticRuleChangeDao {

    @Query("SELECT COUNT(*) FROM download_rules")
    fun observeRuleRows(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs")
    fun observeSongRows(): Flow<Int>

    @Query("SELECT COUNT(*) FROM albums")
    fun observeAlbumRows(): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlist_items")
    fun observePlaylistEntries(): Flow<Int>

    @Query("SELECT COUNT(*) FROM download_queue")
    fun observeQueueRows(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offline_media")
    fun observeOfflineRows(): Flow<Int>
}
