package app.omnisonic.shell

import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Route-table conformance against the pre-drafted table
 * (reference/docs/UI_SCREENS.md §5 — extend only, never rename).
 */
class ShellRoutesTest {

    @Test
    fun `route table matches the §5 contract exactly`() {
        assertThat(ShellRoutes.ALL).containsExactly(
            "home",
            "library",
            "library/{section}",
            "search",
            "settings",
            "providerSetup",
            "librarySync",
            "album/{albumId}",
            "artist/{artistId}",
            "genre/{genreId}",
            "playlist/{playlistId}",
            "nowplaying",
            "queue",
        ).inOrder()
    }

    @Test
    fun `arg builders produce concrete routes for their patterns`() {
        assertThat(ShellRoutes.section("albums")).isEqualTo("library/albums")
        assertThat(ShellRoutes.album(7L)).isEqualTo("album/7")
        assertThat(ShellRoutes.artist(7L)).isEqualTo("artist/7")
        assertThat(ShellRoutes.genre(7L)).isEqualTo("genre/7")
        assertThat(ShellRoutes.playlist(7L)).isEqualTo("playlist/7")
    }

    @Test
    fun `tab ids are their own routes so the persisted tab list routes directly`() {
        MainTab.entries.forEach { tab ->
            assertThat(ShellRoutes.ALL).contains(tab.id)
        }
    }

    @Test
    fun `every onboarding step routes to a registered route`() {
        ShellPreferencesStore.DEFAULT_ONBOARDING_STEPS.forEach { step ->
            assertThat(ShellRoutes.ALL).contains(step)
        }
    }
}
