package app.omnisonic.playback.source

import app.omnisonic.playback.api.source.QueueSource

/**
 * Named no-op seams so later features implement [QueueSource] in place:
 * radio mode fills the tail from the provider's radio list, autoplay appends
 * similar tracks, and "play from search" maps a query onto entries (search
 * plays are otherwise built by the UI; voice/assistant search-play is not
 * planned for 1.0).
 */
object RadioQueueSource : QueueSource

object AutoplayQueueSource : QueueSource

object SearchPlayQueueSource : QueueSource
