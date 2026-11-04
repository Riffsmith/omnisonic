package app.omnisonic.playback.service

import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import app.omnisonic.playback.api.settings.NotificationAction
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class NotificationActionMapperTest {

    @Test
    fun `every slotted action maps to a distinct icon and stable custom action`() {
        val slotted = NotificationAction.entries.filter { it != NotificationAction.NONE }

        val icons = slotted.map { NotificationActionMapper.iconConstant(it) }
        val actions = slotted.map { NotificationActionMapper.customAction(it) }

        assertThat(icons).containsNoDuplicates()
        assertThat(icons).doesNotContain(CommandButton.ICON_UNDEFINED)
        assertThat(actions).containsNoDuplicates()
        assertThat(actions.distinct()).hasSize(slotted.size)
    }

    @Test
    fun `custom action round-trips through fromCustomAction`() {
        NotificationAction.entries
            .filter { it != NotificationAction.NONE }
            .forEach { action ->
                assertThat(NotificationActionMapper.fromCustomAction(NotificationActionMapper.customAction(action)))
                    .isEqualTo(action)
            }
    }

    @Test
    fun `unknown or foreign custom actions are rejected`() {
        assertThat(NotificationActionMapper.fromCustomAction("app.omnisonic.playback.notification.NOPE")).isNull()
        assertThat(NotificationActionMapper.fromCustomAction("other.app.notification.REPEAT")).isNull()
        assertThat(NotificationActionMapper.fromCustomAction("")).isNull()
    }

    @Test
    fun `seek actions map to the matching skip icons`() {
        assertThat(NotificationActionMapper.iconConstant(NotificationAction.SEEK_FORWARD_10))
            .isEqualTo(CommandButton.ICON_SKIP_FORWARD_10)
        assertThat(NotificationActionMapper.iconConstant(NotificationAction.SEEK_FORWARD_30))
            .isEqualTo(CommandButton.ICON_SKIP_FORWARD_30)
        assertThat(NotificationActionMapper.iconConstant(NotificationAction.SEEK_BACK_10))
            .isEqualTo(CommandButton.ICON_SKIP_BACK_10)
        assertThat(NotificationActionMapper.iconConstant(NotificationAction.SEEK_BACK_30))
            .isEqualTo(CommandButton.ICON_SKIP_BACK_30)
    }

    @Test
    fun `none slots drop out and order is preserved`() {
        val slots = NotificationActionMapper.slots(
            listOf(
                NotificationAction.SEEK_FORWARD_10,
                NotificationAction.NONE,
                NotificationAction.REPEAT,
                NotificationAction.NONE,
                NotificationAction.SHUFFLE,
            ),
            chaptersAvailable = false,
        )

        assertThat(slots).containsExactly(
            NotificationAction.SEEK_FORWARD_10,
            NotificationAction.REPEAT,
            NotificationAction.SHUFFLE,
        ).inOrder()
    }

    @Test
    fun `chapter actions are hidden until chapters are available`() {
        val configured = listOf(
            NotificationAction.CHAPTER_NEXT,
            NotificationAction.CHAPTER_PREVIOUS,
            NotificationAction.SEEK_BACK_30,
            NotificationAction.REPEAT,
            NotificationAction.NONE,
        )

        assertThat(NotificationActionMapper.slots(configured, chaptersAvailable = false))
            .containsExactly(NotificationAction.SEEK_BACK_30, NotificationAction.REPEAT).inOrder()
        assertThat(NotificationActionMapper.slots(configured, chaptersAvailable = true))
            .containsExactly(
                NotificationAction.CHAPTER_NEXT,
                NotificationAction.CHAPTER_PREVIOUS,
                NotificationAction.SEEK_BACK_30,
                NotificationAction.REPEAT,
            ).inOrder()
    }

    @Test
    fun `fixed buttons keep their player commands`() {
        assertThat(Player.COMMAND_SEEK_TO_PREVIOUS).isNotEqualTo(Player.COMMAND_PLAY_PAUSE)
        assertThat(Player.COMMAND_PLAY_PAUSE).isNotEqualTo(Player.COMMAND_SEEK_TO_NEXT)
    }
}

/** SessionCommand construction touches framework Bundles, so it runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationActionMapperSessionCommandsTest {

    @org.junit.Test
    fun `session commands cover every slotted action and nothing else`() {
        val commands = NotificationActionMapper.sessionCommands()
        val slotted = NotificationAction.entries.filter { it != NotificationAction.NONE }

        assertThat(commands.commands.map { it.customAction })
            .containsExactlyElementsIn(slotted.map { NotificationActionMapper.customAction(it) })
    }
}
