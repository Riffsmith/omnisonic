package app.omnisonic.core.data.style

import app.omnisonic.core.model.style.CornerRoundness
import app.omnisonic.core.model.style.DarkMode
import app.omnisonic.core.model.style.HomeSection
import app.omnisonic.core.model.style.HomeSectionEntry
import app.omnisonic.core.model.style.OrientationPair
import app.omnisonic.core.model.style.StyleApp
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleEffects
import app.omnisonic.core.model.style.StyleHome
import app.omnisonic.core.model.style.StyleListLayout
import app.omnisonic.core.model.style.StylePages
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StyleMergerTest {

    private val base = StyleDocument(
        id = "base",
        name = "Base",
        app = StyleApp(
            darkMode = DarkMode.DARK,
            cornerScale = CornerRoundness.LARGE,
            effects = StyleEffects(overlayAlpha = 0.6f),
        ),
        home = StyleHome(
            sections = listOf(
                HomeSectionEntry(HomeSection.FAVORITES, visible = false),
                HomeSectionEntry(HomeSection.MOST_PLAYED),
            ),
        ),
        pages = StylePages(
            albums = StyleListLayout(
                columns = OrientationPair(portrait = 4, landscape = 6),
                spacingDp = OrientationPair(portrait = 12f, landscape = 16f),
            ),
        ),
    )

    private val profile = StyleDocument(
        id = "mine",
        name = "Mine",
        app = StyleApp(
            darkMode = DarkMode.LIGHT,
            cornerScale = CornerRoundness.MEDIUM,
            effects = StyleEffects(overlayAlpha = 0.2f),
        ),
        home = StyleHome(sections = listOf(HomeSectionEntry(HomeSection.RECENTLY_ADDED))),
        pages = StylePages(
            albums = StyleListLayout(
                columns = OrientationPair(portrait = 7, landscape = 9),
                spacingDp = OrientationPair(portrait = 4f, landscape = 4f),
            ),
        ),
    )

    private fun merged(result: StyleMergeResult): StyleDocument =
        (result as StyleMergeResult.Merged).document

    private fun reset(result: StyleResetResult): StyleDocument =
        (result as StyleResetResult.Reset).document

    @Test
    fun `a profile wins over the base at every depth`() {
        val document = merged(
            StyleMerger.merge(
                base,
                """{"app":{"cornerScale":"MEDIUM","effects":{"overlayAlpha":0.2}}}""",
            ),
        )

        assertThat(document.app.cornerScale).isEqualTo(CornerRoundness.MEDIUM)
        assertThat(document.app.effects.overlayAlpha).isEqualTo(0.2f)
        assertThat(document.app.darkMode).isEqualTo(DarkMode.DARK)
    }

    @Test
    fun `an absent key keeps the base value underneath`() {
        val document = merged(
            StyleMerger.merge(base, """{"pages":{"albums":{"columns":{"portrait":2}}}}"""),
        )

        assertThat(document.pages.albums.columns).isEqualTo(OrientationPair(portrait = 2, landscape = 6))
        assertThat(document.pages.albums.spacingDp).isEqualTo(base.pages.albums.spacingDp)
        assertThat(document.app.darkMode).isEqualTo(DarkMode.DARK)
    }

    @Test
    fun `a list section is replaced rather than element-merged`() {
        val document = merged(
            StyleMerger.merge(
                base,
                """{"home":{"sections":[{"section":"RECENTLY_PLAYED","visible":true}]}}""",
            ),
        )

        assertThat(document.home.sections).containsExactly(HomeSectionEntry(HomeSection.RECENTLY_PLAYED))
    }

    @Test
    fun `an absent section leaves the base section untouched`() {
        val document = merged(StyleMerger.merge(base, """{"app":{"cornerScale":"MEDIUM"}}"""))

        assertThat(document.home).isEqualTo(base.home)
        assertThat(document.pages).isEqualTo(base.pages)
    }

    @Test
    fun `unknown keys are warned about and dropped`() {
        val result = StyleMerger.merge(base, """{"app":{"mystery":1}}""") as StyleMergeResult.Merged

        assertThat(result.warnings.map { it.path }).containsExactly("app.mystery")
        assertThat(result.document.app).isEqualTo(base.app)
    }

    @Test
    fun `a wrong type is rejected with its path`() {
        val result = StyleMerger.merge(base, """{"app":{"darkMode":7}}""")

        assertThat(result).isInstanceOf(StyleMergeResult.Rejected::class.java)
        assertThat((result as StyleMergeResult.Rejected).firstMessage)
            .isEqualTo("app.darkMode: expected string, got number")
    }

    @Test
    fun `a field reset restores the base value and keeps other overrides`() {
        val document = reset(StyleMerger.resetField(profile, base, "app.cornerScale"))

        assertThat(document.app.cornerScale).isEqualTo(CornerRoundness.LARGE)
        assertThat(document.app.darkMode).isEqualTo(DarkMode.LIGHT)
        assertThat(document.app.effects.overlayAlpha).isEqualTo(0.2f)
    }

    @Test
    fun `a field reset restores one orientation of a pair`() {
        val document = reset(StyleMerger.resetField(profile, base, "pages.albums.columns.portrait"))

        assertThat(document.pages.albums.columns).isEqualTo(OrientationPair(portrait = 4, landscape = 9))
    }

    @Test
    fun `a field reset restores a whole list section`() {
        val document = reset(StyleMerger.resetField(profile, base, "home.sections"))

        assertThat(document.home).isEqualTo(base.home)
    }

    @Test
    fun `a field reset refuses an unknown path and the envelope`() {
        assertThat(StyleMerger.resetField(profile, base, "app.missing"))
            .isEqualTo(StyleResetResult.UnknownPath("app.missing"))
        assertThat(StyleMerger.resetField(profile, base, "id"))
            .isEqualTo(StyleResetResult.UnknownPath("id"))
    }

    @Test
    fun `a section reset restores only that section`() {
        val document = StyleMerger.resetSection(profile, base, StyleSection.PAGES)

        assertThat(document.pages).isEqualTo(base.pages)
        assertThat(document.app).isEqualTo(profile.app)
        assertThat(document.home).isEqualTo(profile.home)
    }

    @Test
    fun `reset all restores the base content and keeps the profile identity`() {
        val document = StyleMerger.resetAll(profile, base)

        assertThat(document.app).isEqualTo(base.app)
        assertThat(document.home).isEqualTo(base.home)
        assertThat(document.pages).isEqualTo(base.pages)
        assertThat(document.nowPlaying).isEqualTo(base.nowPlaying)
        assertThat(document.id).isEqualTo("mine")
        assertThat(document.name).isEqualTo("Mine")
    }
}
