package app.omnisonic.playback.service

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import app.omnisonic.playback.R
import app.omnisonic.playback.api.settings.NotificationAction

/**
 * Pure mapping between settings [NotificationAction]s and their session-facing
 * surface: custom command action string, built-in [CommandButton] icon and
 * label resource. The notification provider and the session callback both go
 * through here so a slot keeps one identity across the notification and the
 * command path.
 */
object NotificationActionMapper {

    const val CUSTOM_ACTION_PREFIX = "app.omnisonic.playback.notification."

    val CHAPTER_ACTIONS = setOf(NotificationAction.CHAPTER_NEXT, NotificationAction.CHAPTER_PREVIOUS)

    fun customAction(action: NotificationAction): String = CUSTOM_ACTION_PREFIX + action.name

    fun fromCustomAction(custom: String): NotificationAction? {
        if (!custom.startsWith(CUSTOM_ACTION_PREFIX)) return null
        return NotificationAction.entries.firstOrNull { customAction(it) == custom }
    }

    fun iconConstant(action: NotificationAction): Int = when (action) {
        NotificationAction.REPEAT -> CommandButton.ICON_REPEAT_ALL
        NotificationAction.SHUFFLE -> CommandButton.ICON_SHUFFLE_ON
        NotificationAction.FAVORITE -> CommandButton.ICON_HEART_UNFILLED
        NotificationAction.STOP -> CommandButton.ICON_STOP
        NotificationAction.RADIO -> CommandButton.ICON_RADIO
        NotificationAction.CHAPTER_NEXT -> CommandButton.ICON_NEXT
        NotificationAction.CHAPTER_PREVIOUS -> CommandButton.ICON_PREVIOUS
        NotificationAction.SEEK_FORWARD_10 -> CommandButton.ICON_SKIP_FORWARD_10
        NotificationAction.SEEK_FORWARD_30 -> CommandButton.ICON_SKIP_FORWARD_30
        NotificationAction.SEEK_BACK_10 -> CommandButton.ICON_SKIP_BACK_10
        NotificationAction.SEEK_BACK_30 -> CommandButton.ICON_SKIP_BACK_30
        NotificationAction.NONE -> CommandButton.ICON_UNDEFINED
    }

    fun labelRes(action: NotificationAction): Int = when (action) {
        NotificationAction.REPEAT -> R.string.notif_action_repeat
        NotificationAction.SHUFFLE -> R.string.notif_action_shuffle
        NotificationAction.FAVORITE -> R.string.notif_action_favorite
        NotificationAction.STOP -> R.string.notif_action_stop
        NotificationAction.RADIO -> R.string.notif_action_radio
        NotificationAction.CHAPTER_NEXT -> R.string.notif_action_chapter_next
        NotificationAction.CHAPTER_PREVIOUS -> R.string.notif_action_chapter_previous
        NotificationAction.SEEK_FORWARD_10 -> R.string.notif_action_seek_forward_10
        NotificationAction.SEEK_FORWARD_30 -> R.string.notif_action_seek_forward_30
        NotificationAction.SEEK_BACK_10 -> R.string.notif_action_seek_back_10
        NotificationAction.SEEK_BACK_30 -> R.string.notif_action_seek_back_30
        NotificationAction.NONE -> R.string.notif_action_stop
    }

    /**
     * Effective slot list: empty slots drop out; chapter actions appear only
     * while chapters exist (the chapters feature lands after Phase 4, so the
     * flag stays false at the call site until then).
     */
    fun slots(configured: List<NotificationAction>, chaptersAvailable: Boolean): List<NotificationAction> =
        configured
            .filter { it != NotificationAction.NONE }
            .filter { chaptersAvailable || it !in CHAPTER_ACTIONS }

    /** All custom commands the session accepts from connected controllers. */
    fun sessionCommands(): SessionCommands = SessionCommands.Builder().apply {
        NotificationAction.entries.filter { it != NotificationAction.NONE }.forEach {
            add(SessionCommand(customAction(it), Bundle.EMPTY))
        }
    }.build()
}
