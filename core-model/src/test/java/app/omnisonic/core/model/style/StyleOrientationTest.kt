package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class StyleOrientationTest {

    private fun fieldNames(type: Class<*>): List<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }

    @Test
    fun `orientation pairs are structural fields, not sibling keys`() {
        assertThat(fieldNames(StyleNowPlaying::class.java)).contains("expanded")
        assertThat(fieldNames(StyleNowPlaying::class.java)).doesNotContain("portrait")
        assertThat(fieldNames(StyleNowPlaying::class.java)).doesNotContain("landscape")
        assertThat(fieldNames(OrientationPair::class.java))
            .containsExactly("portrait", "landscape")
        assertThat(fieldNames(StyleListLayout::class.java)).contains("columns")
        assertThat(fieldNames(StyleListLayout::class.java)).doesNotContain("columnsPortrait")
        assertThat(fieldNames(StyleListLayout::class.java)).doesNotContain("columnsLandscape")
    }

    @Test
    fun `portrait and landscape overrides are independent`() {
        val defaults = StyleNowPlaying()
        val landscape = defaults.expanded.landscape.copy(
            buttons = listOf(StyleControlButton(PlayerAction.STOP)),
        )
        val changed = defaults.copy(
            expanded = defaults.expanded.copy(landscape = landscape),
        )

        assertThat(changed.expanded.portrait).isEqualTo(StyleExpandedPlayer())
        assertThat(changed.expanded.landscape).isEqualTo(landscape)
        assertThat(changed.expanded).isNotEqualTo(defaults.expanded)
    }

    @Test
    fun `expanded defaults are identical per orientation`() {
        val expanded = StyleNowPlaying().expanded

        assertThat(expanded.portrait).isEqualTo(expanded.landscape)
    }

    @Test
    fun `home defaults are an ordered visible list`() {
        val sections = StyleHome().sections

        assertThat(sections.map { it.section }).containsExactly(
            HomeSection.RECENTLY_PLAYED,
            HomeSection.RECENTLY_ADDED,
            HomeSection.MOST_PLAYED,
            HomeSection.FAVORITES,
        ).inOrder()
        assertThat(sections.all { it.visible }).isTrue()
    }

    @Test
    fun `expanded block and button defaults match the current now-playing order`() {
        val player = StyleExpandedPlayer()

        assertThat(player.blocks.map { it.kind }).containsExactly(
            NowPlayingBlockKind.ARTWORK,
            NowPlayingBlockKind.TITLE,
            NowPlayingBlockKind.SUBTITLE,
            NowPlayingBlockKind.PROGRESS,
            NowPlayingBlockKind.CONTROLS,
            NowPlayingBlockKind.QUEUE,
            NowPlayingBlockKind.LYRICS,
        ).inOrder()
        assertThat(player.buttons.map { it.action }).containsExactly(
            PlayerAction.SHUFFLE,
            PlayerAction.PREVIOUS,
            PlayerAction.PLAY_PAUSE,
            PlayerAction.NEXT,
            PlayerAction.REPEAT,
        ).inOrder()
        assertThat(player.text.title.template).isEqualTo("%title%")
        assertThat(player.text.subtitle.template).isEqualTo("%artist%")
    }

    @Test
    fun `compact defaults match the current mini-player order`() {
        val player = StyleCompactPlayer()

        assertThat(player.blocks.map { it.kind }).containsExactly(
            NowPlayingBlockKind.ARTWORK,
            NowPlayingBlockKind.TITLE,
            NowPlayingBlockKind.SUBTITLE,
            NowPlayingBlockKind.CONTROLS,
            NowPlayingBlockKind.PROGRESS,
        ).inOrder()
        assertThat(player.buttons.map { it.action }).containsExactly(
            PlayerAction.PREVIOUS,
            PlayerAction.PLAY_PAUSE,
            PlayerAction.NEXT,
        ).inOrder()
        assertThat(player.progress.appearance).isEqualTo(ProgressAppearance.HAIRLINE)
        assertThat(player.progress.heightDp).isEqualTo(2f)
    }

    @Test
    fun `pages carry a portrait and landscape pair for every list`() {
        val pages = StylePages()

        listOf(pages.albums, pages.artists, pages.genres, pages.songs, pages.playlists)
            .forEach { layout ->
                assertThat(layout.columns).isEqualTo(OrientationPair(portrait = 3, landscape = 5))
                assertThat(layout.spacingDp).isEqualTo(OrientationPair(portrait = 8f, landscape = 8f))
            }

        val changed = pages.copy(
            albums = pages.albums.copy(columns = OrientationPair(portrait = 2, landscape = 4)),
        )
        assertThat(changed.albums.columns.landscape).isEqualTo(4)
        assertThat(changed.artists).isEqualTo(StyleListLayout())
    }

    @Test
    fun `explicit block order and visibility are preserved`() {
        val player = StyleExpandedPlayer(
            blocks = listOf(
                NowPlayingBlock(NowPlayingBlockKind.CONTROLS),
                NowPlayingBlock(NowPlayingBlockKind.ARTWORK, visible = false),
                NowPlayingBlock(NowPlayingBlockKind.PROGRESS),
            ),
        )

        assertThat(player.blocks.map { it.kind }).containsExactly(
            NowPlayingBlockKind.CONTROLS,
            NowPlayingBlockKind.ARTWORK,
            NowPlayingBlockKind.PROGRESS,
        ).inOrder()
        assertThat(player.blocks.map { it.visible }).containsExactly(true, false, true).inOrder()
    }
}
