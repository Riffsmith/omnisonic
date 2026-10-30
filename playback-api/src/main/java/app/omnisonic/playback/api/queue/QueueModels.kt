package app.omnisonic.playback.api.queue

import java.util.concurrent.atomic.AtomicLong

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackQueueEntry(
    val providerId: Long,
    val externalId: String,
    val queueItemId: Long = nextQueueItemId(),
    val songRowId: Long? = null,
    val title: String = "",
    val artistName: String? = null,
    val albumExternalId: String? = null,
    val coverImageId: String? = null,
    val durationMs: Long? = null,
    val isRadioStation: Boolean = false,
) {
    companion object {
        private val COUNTER = AtomicLong(0)
        fun resetIdCounterTo(value: Long) {
            COUNTER.set(value)
        }

        fun nextQueueItemId(): Long = COUNTER.incrementAndGet()
    }
}

/**
 * Playback order indices over [entries]. When shuffle is off the effective
 * order is 0..<entries.size; when on it is [order] (a permutation), so a
 * skip next always advances position in the shuffled order while entries list
 * keeps its original stable order for persistence and UI edits.
 */
data class ShuffleState(
    val order: List<Int>,
    val seed: Long,
)

/**
 * Authoritative queue snapshot. [playIndex] addresses the effective playback
 * order; helpers resolve it to an entries index.
 */
data class QueueState(
    val entries: List<PlaybackQueueEntry> = emptyList(),
    val playIndex: Int = 0,
    val shuffle: ShuffleState? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val sourceType: String? = null,
    val sourceData: String? = null,
) {
    val isNotEmpty
        get() = entries.isNotEmpty()

    val currentEntry: PlaybackQueueEntry?
        get() = entriesIndexAt()?.let { entries.getOrNull(it) }

    fun entriesIndexAt(): Int? {
        val shuffled = shuffle
        if (shuffled == null) {
            return playIndex.takeIf { it in entries.indices }
        }
        val entriesIndex = shuffled.order.getOrNull(playIndex)
        return entriesIndex?.takeIf { it in entries.indices }
    }

    fun effectiveOrder(): List<Int> = shuffle?.order ?: entries.indices.toList()
}

/** Pure shuffle order generation: deterministic given the seed, never maps an index onto itself. */
object ShuffleOrders {
    fun shuffled(count: Int, seed: Long, startIndex: Int = -1): List<Int> {
        val base = (0 until count).toMutableList()
        if (count <= 1) return base.toList()
        val rng = java.util.Random(seed)
        for (i in base.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            base[i] = base[j].also { base[j] = base[i] }
        }
        if (startIndex in 0 until count) {
            val at = base.indexOf(startIndex)
            if (at > 0) {
                val head = base.removeAt(at)
                base.add(0, head)
            }
        }
        return base.toList()
    }
}
