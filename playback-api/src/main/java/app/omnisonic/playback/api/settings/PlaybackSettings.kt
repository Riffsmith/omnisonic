package app.omnisonic.playback.api.settings

import app.omnisonic.playback.api.player.DecoderPreference
import app.omnisonic.playback.api.player.OffloadMode
import app.omnisonic.playback.api.rg.ReplayGainMode
import app.omnisonic.playback.api.rg.RgNormalisation
import kotlinx.coroutines.flow.Flow

enum class TransientLossAction { DUCK, PAUSE }

enum class NotificationAction { NONE, REPEAT, SHUFFLE, FAVORITE, STOP, RADIO, CHAPTER_NEXT, CHAPTER_PREVIOUS, SEEK_FORWARD_10, SEEK_FORWARD_30, SEEK_BACK_10, SEEK_BACK_30 }

/** Read-only playback engine settings; writes live in the settings store impl. */
interface PlaybackSettings {
    val decoderPreference: Flow<DecoderPreference>
    val offloadMode: Flow<OffloadMode>
    val replayGainMode: Flow<ReplayGainMode>
    val replayGainNormalisation: Flow<RgNormalisation>
    val replayGainTargetLoudnessLufs: Flow<Double>
    val preampDb: Flow<Float>
    val mono: Flow<Boolean>
    val cacheFirst: Flow<Boolean>
    val cacheSizeBytes: Flow<Long>
    val transientLossAction: Flow<TransientLossAction>
    val notificationActions: Flow<List<NotificationAction>>
    val shortFadeMs: Flow<Long>
}

object DefaultNotificationActions {
    val SLOT_DEFAULTS = listOf(
        NotificationAction.REPEAT,
        NotificationAction.SHUFFLE,
        NotificationAction.FAVORITE,
        NotificationAction.NONE,
        NotificationAction.NONE,
    )
}
