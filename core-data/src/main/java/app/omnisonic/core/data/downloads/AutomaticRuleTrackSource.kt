package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.AutomaticRuleCatalogDao
import app.omnisonic.core.data.db.entity.SongEntity

/** One downloadable catalog row resolved from a rule, already filtered to a usable external id. */
data class AutomaticRuleTrack(
    val externalId: String,
    val title: String,
    val imagePath: String?,
)

/** Resolution of one rule's current target against the local catalog. */
sealed interface AutomaticRuleTargetResolution {

    data class Resolved(val tracks: List<AutomaticRuleTrack>) : AutomaticRuleTargetResolution

    /** The provider, playlist or album itself is absent; nothing is enqueued. */
    data object StaleTarget : AutomaticRuleTargetResolution

    /** The target exists but its expected songs are not locally resolvable yet; nothing is enqueued. */
    data object Unsynced : AutomaticRuleTargetResolution
}

/**
 * Seam for resolving a rule's current entries/tracks. Read failures are caught
 * by the expander, so a catalog outage is a typed result rather than a partial
 * enqueue or a retry loop.
 */
interface AutomaticRuleTrackSource {

    suspend fun resolve(rule: AutomaticRule, scope: ProfileScope): AutomaticRuleTargetResolution
}

/**
 * Local-catalog resolution for the three confirmed rule kinds. Internal callers
 * pass [ProfileScope.Unrestricted]; the scope still filters a hidden provider
 * from every query for UI-scoped callers.
 *
 * A playlist or album is a complete snapshot or nothing: when any expected
 * entry/track has no locally usable song, the rule is `Unsynced` and writes no
 * rows. Returning the resolvable subset would hand task 8 a partial generation
 * that could later look like a deletion. A genuinely empty playlist/album has
 * an expected count of zero and resolves empty. Favorite songs have no separate
 * expected count, so an empty favorites rule is a valid empty wanted set.
 */
class DatabaseAutomaticRuleTrackSource(
    private val dao: AutomaticRuleCatalogDao,
) : AutomaticRuleTrackSource {

    override suspend fun resolve(
        rule: AutomaticRule,
        scope: ProfileScope,
    ): AutomaticRuleTargetResolution {
        if (!dao.providerExists(rule.providerId, scope)) return AutomaticRuleTargetResolution.StaleTarget
        return when (rule.kind) {
            AutomaticRuleKind.FAVORITES -> {
                val songs = dao.favoriteSongs(rule.providerId, scope)
                AutomaticRuleTargetResolution.Resolved(songs.toTracks())
            }

            AutomaticRuleKind.PLAYLIST -> {
                val playlist = dao.playlistTarget(rule.providerId, rule.targetExternalId, scope)
                    ?: return AutomaticRuleTargetResolution.StaleTarget
                val expected = maxOf(playlist.entryCount, dao.playlistEntryCount(playlist.id, scope))
                val songs = dao.playlistSongs(playlist.id, rule.providerId, scope)
                if (songs.size != expected) {
                    AutomaticRuleTargetResolution.Unsynced
                } else {
                    AutomaticRuleTargetResolution.Resolved(songs.toTracks())
                }
            }

            AutomaticRuleKind.ALBUM -> {
                val album = dao.albumTarget(rule.providerId, rule.targetExternalId, scope)
                    ?: return AutomaticRuleTargetResolution.StaleTarget
                val songs = dao.albumSongs(album.id, rule.providerId, scope)
                if (songs.size < album.songCount) {
                    AutomaticRuleTargetResolution.Unsynced
                } else {
                    AutomaticRuleTargetResolution.Resolved(songs.toTracks())
                }
            }
        }
    }

    /** One queue row per stable external id even when a playlist repeats an entry. */
    private fun List<SongEntity>.toTracks(): List<AutomaticRuleTrack> =
        mapNotNull { song ->
            val externalId = song.externalId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AutomaticRuleTrack(externalId, song.title, song.imagePath)
        }.distinctBy { it.externalId }
}
