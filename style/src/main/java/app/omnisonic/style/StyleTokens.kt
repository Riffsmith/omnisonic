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
    val displayLarge: TypeToken,
    val displayMedium: TypeToken,
    val displaySmall: TypeToken,
    val headlineLarge: TypeToken,
    val headlineMedium: TypeToken,
    val headlineSmall: TypeToken,
    val titleLarge: TypeToken,
    val titleMedium: TypeToken,
    val titleSmall: TypeToken,
    val bodyLarge: TypeToken,
    val bodyMedium: TypeToken,
    val bodySmall: TypeToken,
    val labelLarge: TypeToken,
    val labelMedium: TypeToken,
    val labelSmall: TypeToken,
    val caption: TypeToken,
) {
    fun toTypography(): Typography = Typography(
        displayLarge = displayLarge.toTextStyle(),
        displayMedium = displayMedium.toTextStyle(),
        displaySmall = displaySmall.toTextStyle(),
        headlineLarge = headlineLarge.toTextStyle(),
        headlineMedium = headlineMedium.toTextStyle(),
        headlineSmall = headlineSmall.toTextStyle(),
        titleLarge = titleLarge.toTextStyle(),
        titleMedium = titleMedium.toTextStyle(),
        titleSmall = titleSmall.toTextStyle(),
        bodyLarge = bodyLarge.toTextStyle(),
        bodyMedium = bodyMedium.toTextStyle(),
        bodySmall = bodySmall.toTextStyle(),
        labelLarge = labelLarge.toTextStyle(),
        labelMedium = labelMedium.toTextStyle(),
        labelSmall = labelSmall.toTextStyle(),
    )
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

/** Four roundness stops; `level` is the Phase 9 JSON encoding (0-3). */
enum class CornerScale(
    val level: Int,
    val small: Dp,
    val medium: Dp,
    val large: Dp,
    val extraLarge: Dp,
) {
    DEFAULT(0, 4.dp, 8.dp, 16.dp, 24.dp),
    MEDIUM(1, 6.dp, 12.dp, 22.dp, 32.dp),
    LARGE(2, 10.dp, 18.dp, 30.dp, 42.dp),
    EXTRA_LARGE(3, 14.dp, 24.dp, 38.dp, 56.dp);

    companion object {
        fun fromLevel(level: Int): CornerScale = entries.firstOrNull { it.level == level } ?: DEFAULT
    }
}

@Immutable
data class ShapeTokens(
    val cornerScale: CornerScale = CornerScale.DEFAULT,
    val cover: Dp = 8.dp,
    val pill: Dp = 50.dp,
) {
    val small: Dp get() = cornerScale.small
    val medium: Dp get() = cornerScale.medium
    val large: Dp get() = cornerScale.large
    val extraLarge: Dp get() = cornerScale.extraLarge
}

enum class NavigationLayout { BOTTOM_BAR, RAIL }

enum class NavigationLabelMode { ALL, SELECTED_ONLY, NONE }

@Immutable
data class LayoutTokens(
    val portraitNavigation: NavigationLayout = NavigationLayout.BOTTOM_BAR,
    val landscapeNavigation: NavigationLayout = NavigationLayout.RAIL,
    val portraitLabelMode: NavigationLabelMode = NavigationLabelMode.ALL,
    val landscapeLabelMode: NavigationLabelMode = NavigationLabelMode.ALL,
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

    fun labelModeFor(isLandscape: Boolean): NavigationLabelMode =
        if (isLandscape) landscapeLabelMode else portraitLabelMode

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
        displayLarge = TypeToken(size = 32.sp, lineHeight = 40.sp, weight = FontWeight.Bold),
        displayMedium = TypeToken(size = 28.sp, lineHeight = 36.sp, weight = FontWeight.Bold),
        displaySmall = TypeToken(size = 24.sp, lineHeight = 32.sp, weight = FontWeight.Bold),
        headlineLarge = TypeToken(size = 24.sp, lineHeight = 32.sp, weight = FontWeight.SemiBold),
        headlineMedium = TypeToken(size = 22.sp, lineHeight = 28.sp, weight = FontWeight.SemiBold),
        headlineSmall = TypeToken(size = 20.sp, lineHeight = 26.sp, weight = FontWeight.SemiBold),
        titleLarge = TypeToken(size = 18.sp, lineHeight = 24.sp, weight = FontWeight.Medium),
        titleMedium = TypeToken(size = 16.sp, lineHeight = 22.sp, weight = FontWeight.Medium),
        titleSmall = TypeToken(size = 14.sp, lineHeight = 20.sp, weight = FontWeight.Medium),
        bodyLarge = TypeToken(size = 16.sp, lineHeight = 24.sp, weight = FontWeight.Normal),
        bodyMedium = TypeToken(size = 14.sp, lineHeight = 20.sp, weight = FontWeight.Normal),
        bodySmall = TypeToken(size = 12.sp, lineHeight = 16.sp, weight = FontWeight.Normal),
        labelLarge = TypeToken(size = 14.sp, lineHeight = 20.sp, weight = FontWeight.Medium),
        labelMedium = TypeToken(size = 12.sp, lineHeight = 16.sp, weight = FontWeight.Medium),
        labelSmall = TypeToken(size = 11.sp, lineHeight = 16.sp, weight = FontWeight.Medium),
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
