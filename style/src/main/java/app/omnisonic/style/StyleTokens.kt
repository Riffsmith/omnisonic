package app.omnisonic.style

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single source of every color, size, typography and layout choice. Screens
 * read these tokens (directly or through [OmnisonicTheme]) instead of
 * hard-coding values; Phase 9 swaps the [StyleTokenSource] and no screen is
 * rewritten.
 */
@Immutable
data class StyleTokens(
    val colors: ColorTokens,
    val typography: TypographyTokens,
    val spacing: SpacingTokens,
    val shapes: ShapeTokens,
    val layout: LayoutTokens,
    val imagery: ImageryTokens,
)

@Immutable
data class ColorTokens(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val scrim: Color,
    val coverPlaceholder: Color,
    val onCoverPlaceholder: Color,
    val progressTrack: Color,
    val progressActive: Color,
    val nowPlayingBackground: Color,
    val selectedIndicator: Color,
)

@Immutable
data class TypeToken(
    val size: TextUnit,
    val lineHeight: TextUnit,
    val weight: FontWeight,
    val letterSpacing: TextUnit = 0.sp,
) {
    fun toTextStyle(): TextStyle = TextStyle(
        fontSize = size,
        lineHeight = lineHeight,
        fontWeight = weight,
        letterSpacing = letterSpacing,
    )
}

@Immutable
data class TypographyTokens(
    val display: TypeToken,
    val headline: TypeToken,
    val title: TypeToken,
    val body: TypeToken,
    val label: TypeToken,
    val caption: TypeToken,
) {
    fun toTypography(): Typography {
        val display = display.toTextStyle()
        val headline = headline.toTextStyle()
        val title = title.toTextStyle()
        val body = body.toTextStyle()
        val label = label.toTextStyle()
        val caption = caption.toTextStyle()
        return Typography(
            displayLarge = display,
            displayMedium = display,
            displaySmall = display,
            headlineLarge = headline,
            headlineMedium = headline,
            headlineSmall = headline,
            titleLarge = title,
            titleMedium = title,
            titleSmall = title,
            bodyLarge = body,
            bodyMedium = body,
            bodySmall = caption,
            labelLarge = label,
            labelMedium = label,
            labelSmall = label,
        )
    }
}

@Immutable
data class SpacingTokens(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

@Immutable
data class ShapeTokens(
    val small: Dp = 4.dp,
    val medium: Dp = 8.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    val cover: Dp = 8.dp,
    val pill: Dp = 50.dp,
)

enum class NavigationLayout { BOTTOM_BAR, RAIL }

@Immutable
data class LayoutTokens(
    val portraitNavigation: NavigationLayout = NavigationLayout.BOTTOM_BAR,
    val landscapeNavigation: NavigationLayout = NavigationLayout.RAIL,
    val bottomBarHeight: Dp = 64.dp,
    val railWidth: Dp = 80.dp,
    val gridColumnsPortrait: Int = 3,
    val gridColumnsLandscape: Int = 5,
    val gridSpacing: Dp = 8.dp,
    val pagePadding: Dp = 16.dp,
    val listRowMinHeight: Dp = 64.dp,
    val nowPlayingBarHeight: Dp = 64.dp,
    val detailHeaderHeight: Dp = 240.dp,
    val playerArtSize: Dp = 320.dp,
    val maxContentWidth: Dp = 840.dp,
    val sectionSpacing: Dp = 24.dp,
) {
    fun navigationFor(isLandscape: Boolean): NavigationLayout =
        if (isLandscape) landscapeNavigation else portraitNavigation

    fun gridColumnsFor(isLandscape: Boolean): Int =
        if (isLandscape) gridColumnsLandscape else gridColumnsPortrait
}

@Immutable
data class ImageryTokens(
    val listThumbSize: Dp = 48.dp,
    val gridAspectRatio: Float = 1f,
    val detailArtSize: Dp = 192.dp,
    val nowPlayingArtSize: Dp = 320.dp,
    val placeholderTintAlpha: Float = 0.6f,
)

val DefaultStyleTokens: StyleTokens = StyleTokens(
    colors = ColorTokens(
        primary = Color(0xFF7CC4FF),
        onPrimary = Color(0xFF00344F),
        primaryContainer = Color(0xFF004C6E),
        onPrimaryContainer = Color(0xFFC9E6FF),
        secondary = Color(0xFFB9C8DA),
        onSecondary = Color(0xFF233240),
        secondaryContainer = Color(0xFF394857),
        onSecondaryContainer = Color(0xFFD5E4F6),
        tertiary = Color(0xFFD4BEEF),
        onTertiary = Color(0xFF3A2A52),
        tertiaryContainer = Color(0xFF513F6A),
        onTertiaryContainer = Color(0xFFF0DAFF),
        background = Color(0xFF101014),
        onBackground = Color(0xFFE5E2E9),
        surface = Color(0xFF17171D),
        onSurface = Color(0xFFE5E2E9),
        surfaceVariant = Color(0xFF24242C),
        onSurfaceVariant = Color(0xFFC1C7CE),
        surfaceTint = Color(0xFF7CC4FF),
        inverseSurface = Color(0xFFE5E2E9),
        inverseOnSurface = Color(0xFF303036),
        inversePrimary = Color(0xFF00639B),
        surfaceDim = Color(0xFF101014),
        surfaceBright = Color(0xFF36363D),
        surfaceContainerLowest = Color(0xFF0B0B0F),
        surfaceContainerLow = Color(0xFF14141A),
        surfaceContainer = Color(0xFF1B1B22),
        surfaceContainerHigh = Color(0xFF25252C),
        surfaceContainerHighest = Color(0xFF303038),
        outline = Color(0xFF8B9198),
        outlineVariant = Color(0xFF41474D),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        scrim = Color(0xFF000000),
        coverPlaceholder = Color(0xFF2A2A33),
        onCoverPlaceholder = Color(0xFF9AA0A8),
        progressTrack = Color(0xFF3A3A44),
        progressActive = Color(0xFF7CC4FF),
        nowPlayingBackground = Color(0xFF14141A),
        selectedIndicator = Color(0xFF7CC4FF),
    ),
    typography = TypographyTokens(
        display = TypeToken(size = 28.sp, lineHeight = 34.sp, weight = FontWeight.Bold),
        headline = TypeToken(size = 22.sp, lineHeight = 28.sp, weight = FontWeight.SemiBold),
        title = TypeToken(size = 16.sp, lineHeight = 22.sp, weight = FontWeight.Medium),
        body = TypeToken(size = 14.sp, lineHeight = 20.sp, weight = FontWeight.Normal),
        label = TypeToken(size = 12.sp, lineHeight = 16.sp, weight = FontWeight.Medium),
        caption = TypeToken(size = 11.sp, lineHeight = 14.sp, weight = FontWeight.Normal),
    ),
    spacing = SpacingTokens(),
    shapes = ShapeTokens(),
    layout = LayoutTokens(),
    imagery = ImageryTokens(),
)

/** Phase 9 swaps the implementation; Phase 5 has exactly one hard-coded default. */
fun interface StyleTokenSource {
    fun tokens(): StyleTokens
}

object DefaultStyleTokenSource : StyleTokenSource {
    override fun tokens(): StyleTokens = DefaultStyleTokens
}
