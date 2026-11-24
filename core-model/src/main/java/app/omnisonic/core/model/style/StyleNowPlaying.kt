package app.omnisonic.core.model.style

/**
 * Compact (mini-player) and per-orientation expanded compositions. A null
 * [background] means the resolver uses its default backdrop stack; the stack
 * itself is not duplicated into the document (`docs/omnisonic/style/style-schema.md` §Model).
 */
data class StyleNowPlaying(
    val compact: StyleCompactPlayer = StyleCompactPlayer(),
    val expanded: OrientationPair<StyleExpandedPlayer> = OrientationPair(
        portrait = StyleExpandedPlayer(),
        landscape = StyleExpandedPlayer(),
    ),
    val background: StyleBackdrop? = null,
)

data class StyleCompactPlayer(
    val blocks: List<NowPlayingBlock> = DEFAULT_BLOCKS,
    val buttons: List<StyleControlButton> = DEFAULT_BUTTONS,
    val text: StyleTextBlocks = StyleTextBlocks(),
    val progress: StyleProgress = StyleProgress(
        appearance = ProgressAppearance.HAIRLINE,
        heightDp = 2f,
    ),
) {
    companion object {
        val DEFAULT_BLOCKS: List<NowPlayingBlock> = listOf(
            NowPlayingBlock(NowPlayingBlockKind.ARTWORK),
            NowPlayingBlock(NowPlayingBlockKind.TITLE),
            NowPlayingBlock(NowPlayingBlockKind.SUBTITLE),
            NowPlayingBlock(NowPlayingBlockKind.CONTROLS),
            NowPlayingBlock(NowPlayingBlockKind.PROGRESS),
        )
        /**
         * The shipped mini player draws plain `IconButton`s, so the compact
         * play/pause is neither filled nor large (D-105).
         */
        val DEFAULT_BUTTONS: List<StyleControlButton> = listOf(
            StyleControlButton(PlayerAction.PREVIOUS),
            StyleControlButton(PlayerAction.PLAY_PAUSE),
            StyleControlButton(PlayerAction.NEXT),
        )
    }
}

data class StyleExpandedPlayer(
    val blocks: List<NowPlayingBlock> = DEFAULT_BLOCKS,
    val buttons: List<StyleControlButton> = DEFAULT_BUTTONS,
    /**
     * The shipped player centres a two-line title and a one-line subtitle
     * (D-105); compact text keeps the start-aligned single lines below.
     */
    val text: StyleTextBlocks = DEFAULT_TEXT,
    /** The shipped player always shows elapsed and remaining time (D-105). */
    val progress: StyleProgress = StyleProgress(showRemainingTime = true),
) {
    companion object {
        val DEFAULT_BLOCKS: List<NowPlayingBlock> = listOf(
            NowPlayingBlock(NowPlayingBlockKind.ARTWORK),
            NowPlayingBlock(NowPlayingBlockKind.TITLE),
            NowPlayingBlock(NowPlayingBlockKind.SUBTITLE),
            NowPlayingBlock(NowPlayingBlockKind.PROGRESS),
            NowPlayingBlock(NowPlayingBlockKind.CONTROLS),
            NowPlayingBlock(NowPlayingBlockKind.QUEUE),
            NowPlayingBlock(NowPlayingBlockKind.LYRICS),
        )
        val DEFAULT_TEXT: StyleTextBlocks = StyleTextBlocks(
            title = StyleTextBlock(
                template = "%title%",
                alignment = TextAlignment.CENTER,
                maxLines = 2,
            ),
            subtitle = StyleTextBlock(
                template = "%artist%",
                alignment = TextAlignment.CENTER,
                maxLines = 1,
            ),
        )
        val DEFAULT_BUTTONS: List<StyleControlButton> = listOf(
            StyleControlButton(PlayerAction.SHUFFLE),
            StyleControlButton(PlayerAction.PREVIOUS),
            StyleControlButton(
                action = PlayerAction.PLAY_PAUSE,
                style = ControlButtonStyle.FILLED,
                size = ControlButtonSize.LARGE,
            ),
            StyleControlButton(PlayerAction.NEXT),
            StyleControlButton(PlayerAction.REPEAT),
        )
    }
}

data class NowPlayingBlock(
    val kind: NowPlayingBlockKind = NowPlayingBlockKind.ARTWORK,
    val visible: Boolean = true,
)

enum class NowPlayingBlockKind { ARTWORK, TITLE, SUBTITLE, PROGRESS, CONTROLS, QUEUE, LYRICS }

data class StyleControlButton(
    val action: PlayerAction = PlayerAction.NONE,
    val style: ControlButtonStyle = ControlButtonStyle.DEFAULT,
    val size: ControlButtonSize = ControlButtonSize.MEDIUM,
)

enum class PlayerAction {
    NONE,
    PLAY_PAUSE,
    PREVIOUS,
    NEXT,
    SHUFFLE,
    REPEAT,
    STOP,
    SEEK_BACK_10,
    SEEK_FORWARD_10,
    OPEN_QUEUE,
    OPEN_LYRICS,
    FAVORITE,
    SLEEP_TIMER,
}

enum class ControlButtonStyle { DEFAULT, FILLED, OUTLINED, TEXT }

enum class ControlButtonSize { SMALL, MEDIUM, LARGE }

data class StyleTextBlocks(
    val title: StyleTextBlock = StyleTextBlock(template = "%title%"),
    val subtitle: StyleTextBlock = StyleTextBlock(template = "%artist%"),
)

data class StyleTextBlock(
    val template: String = "",
    val alignment: TextAlignment = TextAlignment.START,
    val maxLines: Int = 1,
)

enum class TextAlignment { START, CENTER, END }

data class StyleProgress(
    val appearance: ProgressAppearance = ProgressAppearance.SLIDER,
    val heightDp: Float = 4f,
    val showRemainingTime: Boolean = false,
)

enum class ProgressAppearance { SLIDER, HAIRLINE }
