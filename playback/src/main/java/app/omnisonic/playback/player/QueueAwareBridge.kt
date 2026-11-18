package app.omnisonic.playback.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.service.NotificationArtworkIdentity
import app.omnisonic.playback.service.NotificationArtworkMetadata
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable inner bridge behind [OmniForwardingPlayer]. Publishes the full
 * effective-order queue as the session-visible timeline plus engine-derived
 * transport state; repeat comes from the queue state and shuffle is never
 * re-advertised because the published order is already the effective order.
 * When the queue does not know about the engine's current item (a direct
 * [app.omnisonic.playback.api.player.OmniPlayer] consumer), the bridge falls
 * back to the player's own current/prepared entries. Commands the bridge
 * cannot serve itself are routed to [Actions]. One instance per
 * [app.omnisonic.playback.api.player.OmniPlayer]; identity never changes for
 * its lifetime and every read happens on the engine application looper.
 */
@UnstableApi
internal class QueueAwareBridge(
    looper: Looper,
    private val queueState: StateFlow<QueueState>,
    private val actions: Actions,
) : SimpleBasePlayer(looper, Clock.DEFAULT) {

    interface Actions {
        /** Seeks within the currently exposed item; [positionMs] C.TIME_UNSET means default position. */
        fun seekInCurrentRequested(positionMs: Long)
        fun queueJumpRequested(orderIndex: Int)
        fun repeatModeRequested(mode: Int)
        fun shuffleRequested(enabled: Boolean)
        fun playWhenReadyRequested(playWhenReady: Boolean)
        fun prepareRequested()
        fun stopRequested()
        fun releaseRequested()
        fun playbackParametersRequested(parameters: PlaybackParameters)
        fun volumeRequested(volume: Float)
        fun trackSelectionParametersRequested(parameters: TrackSelectionParameters)
        fun activeEngine(): Player?
        fun currentEntries(): Pair<PlaybackQueueEntry?, PlaybackQueueEntry?>
    }

    private var released = false
    private var publishedError: PlaybackException? = null
    private var publishedIndex = 0

    // Cover bytes for the session metadata, published from the notification
    // provider. The metadata is built lazily on the first [getState] that sees
    // the matching current entry (the entry's title/artist live there), then
    // reused for every position tick: rebuilding it would clone the whole
    // image once per tick.
    private class SessionArtwork(
        val identity: NotificationArtworkIdentity,
        val png: ByteArray,
        var metadataEntryId: Long? = null,
        var metadata: MediaMetadata? = null,
    )

    private val looperHandler = Handler(looper)

    // Written on the application looper, read from the provider's thread for
    // the fast-path no-op check below.
    @Volatile
    private var sessionArtwork: SessionArtwork? = null

    fun markReleased() {
        released = true
        publishedError = null
        invalidateState()
    }

    fun publishError(error: PlaybackException) {
        publishedError = error
        invalidateState()
    }

    fun clearPublishedError() {
        publishedError = null
    }

    fun republish() {
        if (!released) invalidateState()
    }

    /**
     * Attaches [png] to the current queue entry's exposed metadata and
     * republishes, so media3 fills `METADATA_KEY_ALBUM_ART` for the session.
     * Safe from any thread; posted to the application looper because
     * `invalidateState()` is application-thread-only. An unchanged identity
     * and byte-array instance is a no-op, which stops the repaint feedback
     * loop (the repaint triggers another notification build).
     */
    fun publishSessionArtwork(identity: NotificationArtworkIdentity, png: ByteArray) {
        // Fast path only: the caller's thread can miss a just-posted value, so
        // the authoritative no-op check runs again on the looper, where the
        // state is mutated and every write is ordered.
        if (isCurrentArtwork(identity, png)) return
        looperHandler.post {
            if (released) return@post
            if (isCurrentArtwork(identity, png)) return@post
            sessionArtwork = SessionArtwork(identity, png)
            invalidateState()
        }
    }

    private fun isCurrentArtwork(identity: NotificationArtworkIdentity, png: ByteArray): Boolean {
        val current = sessionArtwork ?: return false
        return current.identity == identity && current.png === png
    }

    override fun getState(): State {
        val builder = State.Builder()
        if (released) {
            return builder
                .setAvailableCommands(Player.Commands.EMPTY)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }
        val engine = actions.activeEngine()
            ?: return builder
                .setAvailableCommands(Player.Commands.EMPTY)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        val queue = queueState.value
        val (currentEntry, preparedEntry) = actions.currentEntries()
        val playlist = playlistFor(queue, currentEntry, preparedEntry, engine)
        val currentIndex = currentIndexFor(queue, currentEntry, playlist.size)
        publishedIndex = currentIndex
        val engineState = playbackStateOf(engine, playlist.isEmpty())
        return builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex)
            .setAvailableCommands(availableCommands(queue))
            .setPlaybackState(engineState)
            .setPlayWhenReady(engine.playWhenReady, playWhenReadyChangeReason(engine))
            .setPlaybackSuppressionReason(engine.playbackSuppressionReason)
            .setPlaybackParameters(engine.playbackParameters)
            .setRepeatMode(QueueCommandPolicy.toPlayerRepeatMode(queue.repeatMode))
            .setShuffleModeEnabled(false)
            .setIsLoading(engine.isLoading && engineState != Player.STATE_IDLE && engineState != Player.STATE_ENDED)
            .setAudioAttributes(engine.audioAttributes)
            .setVolume(engine.volume)
            .setTrackSelectionParameters(engine.trackSelectionParameters)
            .setPlayerError(publishedError?.takeIf { engineState == Player.STATE_IDLE })
            .setContentPositionMs(contentPositionMs(engine, engineState))
            .setContentBufferedPositionMs(
                SimpleBasePlayer.PositionSupplier.getConstant(
                    if (engineState == Player.STATE_IDLE) 0L else engine.bufferedPosition,
                ),
            )
            .setTotalBufferedDurationMs(
                SimpleBasePlayer.PositionSupplier.getConstant(
                    if (engineState == Player.STATE_IDLE) 0L else engine.totalBufferedDuration,
                ),
            )
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setMaxSeekToPreviousPositionMs(MAX_SEEK_TO_PREVIOUS_MS)
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex == publishedIndex) {
            actions.seekInCurrentRequested(positionMs)
        } else {
            actions.queueJumpRequested(mediaItemIndex)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        actions.repeatModeRequested(repeatMode)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        actions.shuffleRequested(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        actions.playWhenReadyRequested(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        actions.prepareRequested()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        actions.stopRequested()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        actions.releaseRequested()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        actions.playbackParametersRequested(playbackParameters)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        actions.volumeRequested(volume)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        actions.trackSelectionParametersRequested(trackSelectionParameters)
        return Futures.immediateVoidFuture()
    }

    private fun playlistFor(
        queue: QueueState,
        currentEntry: PlaybackQueueEntry?,
        preparedEntry: PlaybackQueueEntry?,
        engine: Player,
    ): List<MediaItemData> {
        if (queue.entries.isNotEmpty()) {
            // The session's current item is the queue's selected entry even
            // before the engine has loaded it (paused/idle after a restore),
            // so artwork must follow the queue pointer when the engine has no
            // current entry yet.
            val exposedCurrent = currentEntry ?: queue.currentEntry
            return queue.effectiveOrder().mapNotNull { index ->
                queue.entries.getOrNull(index)?.let { entry ->
                    mediaItemDataFor(entry, entry.queueItemId == exposedCurrent?.queueItemId, engine)
                }
            }
        }
        val entries = listOfNotNull(currentEntry, preparedEntry?.takeIf { it != currentEntry })
        return entries.map { entry -> mediaItemDataFor(entry, entry == currentEntry, engine) }
    }

    private fun currentIndexFor(queue: QueueState, currentEntry: PlaybackQueueEntry?, playlistSize: Int): Int {
        if (playlistSize == 0) return 0
        val order = queue.effectiveOrder()
        val orderPosition = currentEntry?.let { entry ->
            order.indexOfFirst { index -> queue.entries.getOrNull(index)?.queueItemId == entry.queueItemId }
                .takeIf { it >= 0 }
        }
        return (orderPosition ?: queue.playIndex).coerceIn(0, playlistSize - 1)
    }

    private fun mediaItemDataFor(entry: PlaybackQueueEntry, isCurrent: Boolean, engine: Player): MediaItemData {
        val durationUs = entry.durationMs?.let { C.msToUs(it) } ?: C.TIME_UNSET
        val metadata = metadataFor(entry, isCurrent)
        return MediaItemData.Builder(entry.queueItemId)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(entry.queueItemId.toString())
                    .setMediaMetadata(metadata)
                    .build(),
            )
            .setMediaMetadata(metadata)
            .setTracks(tracksFor(entry, isCurrent, engine))
            .setDurationUs(durationUs)
            .setIsSeekable(!entry.isRadioStation)
            .setIsDynamic(entry.isRadioStation)
            .build()
    }

    /**
     * Cover identity rides as extras; the notification resolves it, never a raw
     * URL. Only the current entry whose identity matches the published artwork
     * carries the artwork bytes, and that [MediaMetadata] is cached per
     * artwork + entry so position ticks never rebuild it.
     */
    private fun metadataFor(entry: PlaybackQueueEntry, isCurrent: Boolean): MediaMetadata {
        val art = sessionArtwork
        if (isCurrent && art != null && art.identity.matches(entry)) {
            val cached = art.metadata
            if (cached != null && art.metadataEntryId == entry.queueItemId) return cached
            return metadataBuilderFor(entry)
                .setArtworkData(art.png)
                .build()
                .also {
                    art.metadata = it
                    art.metadataEntryId = entry.queueItemId
                }
        }
        return metadataBuilderFor(entry).build()
    }

    private fun metadataBuilderFor(entry: PlaybackQueueEntry): MediaMetadata.Builder {
        val builder = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.artistName)
        val coverImageId = entry.coverImageId
        if (!coverImageId.isNullOrBlank()) {
            builder.setExtras(NotificationArtworkMetadata.extras(entry.providerId, coverImageId))
        }
        return builder
    }

    /** [NotificationArtworkIdentity.sizePx] is a provider request concern, not an entry field. */
    private fun NotificationArtworkIdentity.matches(entry: PlaybackQueueEntry): Boolean =
        providerId == entry.providerId && imageId == entry.coverImageId

    private fun tracksFor(entry: PlaybackQueueEntry, isCurrent: Boolean, engine: Player): Tracks {
        val engineItem = if (engine.playbackState == Player.STATE_IDLE) null else engine.currentMediaItem
        return if (isCurrent && engineItem?.mediaId == entry.queueItemId.toString()) engine.currentTracks else Tracks.EMPTY
    }

    private fun playbackStateOf(engine: Player, playlistEmpty: Boolean): Int {
        val engineState = engine.playbackState
        if (playlistEmpty) return if (engineState == Player.STATE_ENDED) Player.STATE_ENDED else Player.STATE_IDLE
        return engineState
    }

    private fun playWhenReadyChangeReason(engine: Player): Int =
        if (engine.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        } else {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        }

    private fun contentPositionMs(engine: Player, engineState: Int): Long =
        if (engineState == Player.STATE_IDLE) 0L else engine.currentPosition

    private fun availableCommands(queue: QueueState): Player.Commands {
        val hasQueue = queue.entries.isNotEmpty()
        return Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_GET_TEXT,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
            )
            .addIf(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, hasQueue)
            .addIf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, hasQueue)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, QueueCommandPolicy.canSkipNext(queue))
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, QueueCommandPolicy.canSkipNext(queue))
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, QueueCommandPolicy.canSkipPrevious(queue))
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, QueueCommandPolicy.canSkipPrevious(queue))
            .build()
    }

    private companion object {
        const val SEEK_BACK_MS = 5_000L
        const val SEEK_FORWARD_MS = 15_000L
        const val MAX_SEEK_TO_PREVIOUS_MS = 3_000L
    }
}
