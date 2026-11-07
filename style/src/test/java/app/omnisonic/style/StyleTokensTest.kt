package app.omnisonic.style

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.unit.dp
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
    }

    @Test
    fun typographyRolesMapToMaterialRoles() {
        val tokens = DefaultStyleTokens.typography
        val typography = tokens.toTypography()

        assertThat(typography.displayLarge.fontSize).isEqualTo(tokens.display.size)
        assertThat(typography.headlineMedium.fontSize).isEqualTo(tokens.headline.size)
        assertThat(typography.titleMedium.fontSize).isEqualTo(tokens.title.size)
        assertThat(typography.bodyMedium.fontSize).isEqualTo(tokens.body.size)
        assertThat(typography.labelLarge.fontSize).isEqualTo(tokens.label.size)
        assertThat(typography.bodySmall.fontSize).isEqualTo(tokens.caption.size)
        assertThat(tokens.display.size.value).isGreaterThan(tokens.body.size.value)
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
    fun orientationSelectsNavigationAndGridColumns() {
        val layout = DefaultStyleTokens.layout

        assertThat(layout.navigationFor(isLandscape = false)).isEqualTo(NavigationLayout.BOTTOM_BAR)
        assertThat(layout.navigationFor(isLandscape = true)).isEqualTo(NavigationLayout.RAIL)
        assertThat(layout.gridColumnsFor(isLandscape = false)).isEqualTo(layout.gridColumnsPortrait)
        assertThat(layout.gridColumnsFor(isLandscape = true)).isEqualTo(layout.gridColumnsLandscape)
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
    }
}
