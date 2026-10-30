package app.omnisonic.playback.api.graph

enum class BrowseKind { ROOT, FOLDER, TRACK }

data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val kind: BrowseKind,
    val isPlayable: Boolean,
    val playableEntry: app.omnisonic.playback.api.queue.PlaybackQueueEntry? = null,
    val sizeHint: Int? = null,
)

data class BrowsePage(
    val items: List<BrowseNode>,
    val hasNextPage: Boolean,
)

/**
 * Library browse seam for controller devices (Android Auto later). The
 * Phase 4 service surface consumes the root only; real nodes arrive with the
 * library feature.
 */
interface BrowseTreeProvider {
    suspend fun root(): BrowseNode
    suspend fun children(parentId: String, page: Int): BrowsePage
    suspend fun node(id: String): BrowseNode?
}

object EmptyBrowseTreeProvider : BrowseTreeProvider {
    override suspend fun root(): BrowseNode = BrowseNode(
        id = ROOT_ID,
        title = "OMNISONIC",
        kind = BrowseKind.ROOT,
        isPlayable = false,
    )

    override suspend fun children(parentId: String, page: Int): BrowsePage = BrowsePage(items = emptyList(), hasNextPage = false)

    override suspend fun node(id: String): BrowseNode? = root().takeIf { it.id == id }

    const val ROOT_ID = "omnisonic_root"
}
