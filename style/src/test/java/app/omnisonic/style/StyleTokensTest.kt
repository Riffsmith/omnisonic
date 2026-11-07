package app.omnisonic.style

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StyleTokensTest {

    @Test
    fun defaultTokensCoverEveryDimensionWithPositiveValues() {
        val tokens = DefaultStyleTokens
        val colors = tokens.colors
        val colorValues = listOf(
            colors.primary, colors.onPrimary, colors.primaryContainer, colors.onPrimaryContainer,
            colors.secondary, colors.onSecondary, colors.secondaryContainer, colors.onSecondaryContainer,
            colors.tertiary, colors.onTertiary, colors.tertiaryContainer, colors.onTertiaryContainer,
            colors.background, colors.onBackground,
            colors.surface, colors.onSurface, colors.surfaceVariant, colors.onSurfaceVariant,
            colors.surfaceTint, colors.inverseSurface, colors.inverseOnSurface, colors.inversePrimary,
            colors.surfaceDim, colors.surfaceBright, colors.surfaceContainerLowest,
            colors.surfaceContainerLow, colors.surfaceContainer, colors.surfaceContainerHigh,
            colors.surfaceContainerHighest,
            colors.outline, colors.outlineVariant, colors.error, colors.onError,
            colors.errorContainer, colors.onErrorContainer, colors.scrim,
            colors.coverPlaceholder, colors.onCoverPlaceholder, colors.progressTrack,
            colors.progressActive, colors.nowPlayingBackground, colors.selectedIndicator,
        )
        colorValues.forEach { assertThat(it.alpha).isWithin(0.001f).of(1f) }

        val spacing = tokens.spacing
        val spacingValues = listOf(spacing.xxs, spacing.xs, spacing.sm, spacing.md, spacing.lg, spacing.xl, spacing.xxl)
        spacingValues.forEach { assertThat(it.value).isAtLeast(0f) }
        assertThat(spacingValues).isInOrder()

        val layout = tokens.layout
        assertThat(layout.gridColumnsPortrait).isAtLeast(1)
        assertThat(layout.gridColumnsLandscape).isAtLeast(layout.gridColumnsPortrait)
        assertThat(layout.bottomBarHeight.value).isGreaterThan(0f)
        assertThat(layout.railWidth.value).isGreaterThan(0f)
        assertThat(tokens.imagery.gridAspectRatio).isGreaterThan(0f)

        val shapes = tokens.shapes
        listOf(shapes.small, shapes.medium, shapes.large, shapes.extraLarge, shapes.cover, shapes.pill)
            .forEach { assertThat(it.value).isAtLeast(0f) }
    }

    @Test
    fun typographyRampMapsOneToOneToMaterialRoles() {
        val tokens = DefaultStyleTokens.typography
        val typography = tokens.toTypography()

        val roleMappings = listOf(
            typography.displayLarge to tokens.displayLarge,
            typography.displayMedium to tokens.displayMedium,
            typography.displaySmall to tokens.displaySmall,
            typography.headlineLarge to tokens.headlineLarge,
            typography.headlineMedium to tokens.headlineMedium,
            typography.headlineSmall to tokens.headlineSmall,
            typography.titleLarge to tokens.titleLarge,
            typography.titleMedium to tokens.titleMedium,
            typography.titleSmall to tokens.titleSmall,
            typography.bodyLarge to tokens.bodyLarge,
            typography.bodyMedium to tokens.bodyMedium,
            typography.bodySmall to tokens.bodySmall,
            typography.labelLarge to tokens.labelLarge,
            typography.labelMedium to tokens.labelMedium,
            typography.labelSmall to tokens.labelSmall,
        )
        roleMappings.forEach { (material, token) ->
            assertThat(material).isEqualTo(token.toTextStyle())
        }

        assertThat(tokens.caption.size).isEqualTo(11.sp)
        assertThat(tokens.displayLarge.size.value).isAtLeast(tokens.displaySmall.size.value)
        assertThat(tokens.headlineLarge.size.value).isAtLeast(tokens.headlineSmall.size.value)
        assertThat(tokens.titleLarge.size.value).isAtLeast(tokens.titleSmall.size.value)
        assertThat(tokens.bodyLarge.size.value).isAtLeast(tokens.bodySmall.size.value)
        assertThat(tokens.labelLarge.size.value).isAtLeast(tokens.labelSmall.size.value)
        assertThat(tokens.displayLarge.size.value).isGreaterThan(tokens.bodyMedium.size.value)
    }

    @Test
    fun colorTokensMapToMaterialColorScheme() {
        val tokens = DefaultStyleTokens.colors
        val scheme: ColorScheme = tokens.toColorScheme()

        assertThat(scheme.primary).isEqualTo(tokens.primary)
        assertThat(scheme.background).isEqualTo(tokens.background)
        assertThat(scheme.surfaceVariant).isEqualTo(tokens.surfaceVariant)
        assertThat(scheme.surfaceContainerHighest).isEqualTo(tokens.surfaceContainerHighest)
        assertThat(scheme.inversePrimary).isEqualTo(tokens.inversePrimary)
        assertThat(scheme.errorContainer).isEqualTo(tokens.errorContainer)
        assertThat(scheme.error).isEqualTo(tokens.error)
        assertThat(scheme.scrim).isEqualTo(tokens.scrim)
    }

    @Test
    fun shapeTokensMapToMaterialShapes() {
        val tokens = DefaultStyleTokens.shapes
        val shapes = tokens.toShapes()

        assertThat(shapes.medium).isEqualTo(RoundedCornerShape(tokens.medium))
        assertThat(shapes.large).isEqualTo(RoundedCornerShape(tokens.large))
        assertThat(shapes.extraLarge.topStart).isEqualTo(CornerSize(tokens.extraLarge))
    }

    @Test
    fun cornerScaleDerivesAllFourRadiiAndLeavesCoverAndPillStable() {
        val defaults = DefaultStyleTokens.shapes
        assertThat(defaults.cornerScale).isEqualTo(CornerScale.DEFAULT)
        assertThat(defaults.small).isEqualTo(4.dp)
        assertThat(defaults.medium).isEqualTo(8.dp)
        assertThat(defaults.large).isEqualTo(16.dp)
        assertThat(defaults.extraLarge).isEqualTo(24.dp)

        CornerScale.entries.forEach { scale ->
            val shapes = defaults.copy(cornerScale = scale)
            assertThat(shapes.small).isEqualTo(scale.small)
            assertThat(shapes.medium).isEqualTo(scale.medium)
            assertThat(shapes.large).isEqualTo(scale.large)
            assertThat(shapes.extraLarge).isEqualTo(scale.extraLarge)
            assertThat(shapes.cover).isEqualTo(defaults.cover)
            assertThat(shapes.pill).isEqualTo(defaults.pill)
        }

        CornerScale.entries.zipWithNext().forEach { (lower, higher) ->
            assertThat(higher.extraLarge.value).isGreaterThan(lower.extraLarge.value)
            assertThat(higher.small.value).isGreaterThan(lower.small.value)
        }

        CornerScale.entries.forEach { assertThat(CornerScale.fromLevel(it.level)).isEqualTo(it) }
        assertThat(CornerScale.fromLevel(7)).isEqualTo(CornerScale.DEFAULT)
        assertThat(CornerScale.fromLevel(-1)).isEqualTo(CornerScale.DEFAULT)
    }

    @Test
    fun orientationSelectsNavigationLabelModeAndGridColumns() {
        val layout = DefaultStyleTokens.layout

        assertThat(layout.navigationFor(isLandscape = false)).isEqualTo(NavigationLayout.BOTTOM_BAR)
        assertThat(layout.navigationFor(isLandscape = true)).isEqualTo(NavigationLayout.RAIL)
        assertThat(layout.gridColumnsFor(isLandscape = false)).isEqualTo(layout.gridColumnsPortrait)
        assertThat(layout.gridColumnsFor(isLandscape = true)).isEqualTo(layout.gridColumnsLandscape)
        assertThat(layout.labelModeFor(isLandscape = false)).isEqualTo(NavigationLabelMode.ALL)
        assertThat(layout.labelModeFor(isLandscape = true)).isEqualTo(NavigationLabelMode.ALL)
    }

    @Test
    fun labelModeOverridesResolvePerOrientation() {
        val layout = DefaultStyleTokens.layout.copy(
            portraitLabelMode = NavigationLabelMode.SELECTED_ONLY,
            landscapeLabelMode = NavigationLabelMode.NONE,
        )

        assertThat(layout.labelModeFor(isLandscape = false)).isEqualTo(NavigationLabelMode.SELECTED_ONLY)
        assertThat(layout.labelModeFor(isLandscape = true)).isEqualTo(NavigationLabelMode.NONE)
    }

    @Test
    fun defaultSourceReturnsTheHardCodedDefaultAndCanBeReplaced() {
        assertThat(DefaultStyleTokenSource.tokens()).isSameInstanceAs(DefaultStyleTokens)

        val alternate = DefaultStyleTokens.copy(
            layout = DefaultStyleTokens.layout.copy(portraitNavigation = NavigationLayout.RAIL),
        )
        val alternateSource = StyleTokenSource { alternate }

        assertThat(alternateSource.tokens()).isSameInstanceAs(alternate)
        assertThat(alternateSource.tokens().layout.navigationFor(isLandscape = false))
            .isEqualTo(NavigationLayout.RAIL)
    }

    @Test
    fun customSchemeKeepsUnrelatedSizesStable() {
        val alternate = DefaultStyleTokens.copy(
            colors = DefaultStyleTokens.colors.copy(primary = DefaultStyleTokens.colors.tertiary),
        )

        assertThat(alternate.colors.primary).isEqualTo(DefaultStyleTokens.colors.tertiary)
        assertThat(alternate.colors.background).isEqualTo(DefaultStyleTokens.colors.background)
        assertThat(alternate.spacing).isEqualTo(DefaultStyleTokens.spacing)
        assertThat(alternate.typography).isEqualTo(DefaultStyleTokens.typography)
        assertThat(DefaultStyleTokens.layout.pagePadding).isEqualTo(16.dp)

        val reshaped = DefaultStyleTokens.copy(
            shapes = DefaultStyleTokens.shapes.copy(cornerScale = CornerScale.LARGE),
        )
        assertThat(reshaped.shapes.large).isEqualTo(CornerScale.LARGE.large)
        assertThat(reshaped.shapes.extraLarge).isEqualTo(CornerScale.LARGE.extraLarge)
        assertThat(reshaped.shapes.cover).isEqualTo(DefaultStyleTokens.shapes.cover)
        assertThat(reshaped.colors).isEqualTo(DefaultStyleTokens.colors)
        assertThat(reshaped.typography).isEqualTo(DefaultStyleTokens.typography)
    }

    @Test
    fun effectDefaultsAreStableAndGatesDefaultToSafeValues() {
        val effects = DefaultStyleTokens.effects
        assertThat(effects).isEqualTo(EffectTokens())
        assertThat(effects.backdropPreset).isEqualTo(BackdropPreset.SOLID)
        assertThat(effects.backdropLegibilityShield).isTrue()
        assertThat(effects.progressiveBlur).isEqualTo(ProgressiveBlurMode.AUTO)
        assertThat(effects.vignetteStrength).isEqualTo(0f)
    }

    @Test
    fun effectValuesStayWithinRange() {
        val effects = DefaultStyleTokens.effects
        listOf(
            effects.backdropStrength,
            effects.backdropOverlayAlpha,
            effects.vignetteStrength,
            effects.navBarAlpha,
            effects.tabBarAlpha,
        ).forEach { alpha ->
            assertThat(alpha).isAtLeast(0f)
            assertThat(alpha).isAtMost(1f)
        }
        assertThat(effects.backdropBlurRadiusDp.value).isAtLeast(0f)

        ProgressiveBlurMode.entries.forEach { mode ->
            assertThat(mode.active(apiLevel = 33)).isEqualTo(mode != ProgressiveBlurMode.OFF)
            assertThat(mode.active(apiLevel = 32)).isEqualTo(mode == ProgressiveBlurMode.ON)
            assertThat(mode.active(apiLevel = 29)).isEqualTo(mode == ProgressiveBlurMode.ON)
        }

        assertThat(effects.vignetteRequested(apiLevel = 29)).isFalse()
        assertThat(effects.vignetteRequested(apiLevel = 40)).isFalse()
        val vignetteOn = effects.copy(vignetteStrength = 0.3f)
        assertThat(vignetteOn.vignetteRequested(apiLevel = 32)).isFalse()
        assertThat(vignetteOn.vignetteRequested(apiLevel = 33)).isTrue()
    }

    @Test
    fun effectOverridesLeaveOtherTokenGroupsUnchanged() {
        val alternate = DefaultStyleTokens.copy(
            effects = DefaultStyleTokens.effects.copy(backdropPreset = BackdropPreset.GRADIENT),
        )

        assertThat(alternate.effects.backdropPreset).isEqualTo(BackdropPreset.GRADIENT)
        assertThat(alternate.effects.backdropOverlayAlpha)
            .isEqualTo(DefaultStyleTokens.effects.backdropOverlayAlpha)
        assertThat(alternate.colors).isEqualTo(DefaultStyleTokens.colors)
        assertThat(alternate.layout).isEqualTo(DefaultStyleTokens.layout)
        assertThat(alternate.motion).isEqualTo(DefaultStyleTokens.motion)
        assertThat(alternate.fonts).isEqualTo(DefaultStyleTokens.fonts)

        val motionOverride = DefaultStyleTokens.copy(
            motion = DefaultStyleTokens.motion.copy(durationScale = 2f),
        )
        assertThat(motionOverride.effects).isEqualTo(DefaultStyleTokens.effects)
        assertThat(motionOverride.fonts).isEqualTo(DefaultStyleTokens.fonts)
    }

    @Test
    fun motionDefaultsAreStableAndValid() {
        val motion = DefaultStyleTokens.motion
        assertThat(motion).isEqualTo(MotionTokens())
        assertThat(motion.durationScale).isEqualTo(1f)

        listOf(motion.tabTransition, motion.queueReorder, motion.artCrossfade)
            .forEach { tween -> assertThat(tween.durationMs).isGreaterThan(0) }
        assertThat(motion.nowPlayingExpand.dampingRatio).isGreaterThan(0f)
        assertThat(motion.nowPlayingExpand.stiffness).isGreaterThan(0f)

        MotionEasingProfile.entries.forEach { profile ->
            assertThat(profile.toEasing()).isSameInstanceAs(profile.toEasing())
        }
        val curves = MotionEasingProfile.entries.map { it.toEasing() }
        assertThat(curves.distinct()).hasSize(curves.size)
    }

    @Test
    fun fontTokensDefaultToPlatformFamiliesAndIsolateOverrides() {
        val fonts = DefaultStyleTokens.fonts
        assertThat(fonts).isEqualTo(FontTokens())
        assertThat(fonts.fontFamilies.title).isNull()
        assertThat(fonts.fontFamilies.body).isNull()
        assertThat(fonts.fontFamilies.lyrics).isNull()
        assertThat(fonts.fontFamilies.nav).isNull()
        assertThat(fonts.customFontUris).isEmpty()

        val titled = fonts.copy(fontFamilies = fonts.fontFamilies.copy(title = "Serif Display"))
        assertThat(titled.fontFamilies.title).isEqualTo("Serif Display")
        assertThat(titled.fontFamilies.body).isNull()
        assertThat(titled.fontFamilies.lyrics).isNull()
        assertThat(titled.fontFamilies.nav).isNull()
        assertThat(titled.customFontUris).isEmpty()

        val imported = fonts.copy(
            customFontUris = listOf(CustomFontFace(id = "face-1", uri = "content://fonts/face1.ttf")),
        )
        assertThat(imported.customFontUris).hasSize(1)
        assertThat(imported.fontFamilies).isEqualTo(fonts.fontFamilies)
        assertThat(imported.fontFamilies.title).isNull()
    }

    @Test
    fun layoutTokensResolveTabSwipeAndBarStylePerOrientation() {
        val layout = DefaultStyleTokens.layout

        assertThat(layout.tabSwipeModeFor(isLandscape = false)).isEqualTo(TabSwipeMode.ENABLED)
        assertThat(layout.tabSwipeModeFor(isLandscape = true)).isEqualTo(TabSwipeMode.ENABLED)
        assertThat(layout.tabBarStyleFor(isLandscape = false)).isEqualTo(TabBarStyle.VISIBLE)
        assertThat(layout.tabBarStyleFor(isLandscape = true)).isEqualTo(TabBarStyle.VISIBLE)

        val configured = layout.copy(
            portraitTabSwipeMode = TabSwipeMode.DISABLED,
            landscapeTabSwipeMode = TabSwipeMode.ENABLED,
            portraitTabBarStyle = TabBarStyle.HIDDEN,
            landscapeTabBarStyle = TabBarStyle.VISIBLE,
        )
        assertThat(configured.tabSwipeModeFor(isLandscape = false)).isEqualTo(TabSwipeMode.DISABLED)
        assertThat(configured.tabSwipeModeFor(isLandscape = true)).isEqualTo(TabSwipeMode.ENABLED)
        assertThat(configured.tabBarStyleFor(isLandscape = false)).isEqualTo(TabBarStyle.HIDDEN)
        assertThat(configured.tabBarStyleFor(isLandscape = true)).isEqualTo(TabBarStyle.VISIBLE)
    }
}
