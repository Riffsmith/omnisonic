package app.omnisonic.core.data.search

/** The catalog kinds a recents entry can point at. */
enum class RecentPickKind { SONG, ALBUM, ARTIST, GENRE, PLAYLIST }

/**
 * An immutable display snapshot of a tapped search result. The catalog row id
 * is the authority for what a click opens; the other fields only render the
 * recents row, so a retitled or re-imaged row follows the catalog on click
 * while the stored snapshot stays until it is picked again.
 */
data class RecentPick(
    val kind: RecentPickKind,
    val id: Long,
    val providerId: Long,
    val externalId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val imagePath: String? = null,
    val durationMs: Long? = null,
)
