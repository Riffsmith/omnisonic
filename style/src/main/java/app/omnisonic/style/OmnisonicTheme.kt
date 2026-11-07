package app.omnisonic.style

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalStyleTokens = staticCompositionLocalOf { DefaultStyleTokens }

object OmnisonicTheme {
    val tokens: StyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalStyleTokens.current
}

@Composable
fun OmnisonicTheme(
    tokens: StyleTokens = DefaultStyleTokens,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStyleTokens provides tokens) {
        MaterialTheme(
            colorScheme = tokens.colors.toColorScheme(),
            typography = tokens.typography.toTypography(),
            shapes = tokens.shapes.toShapes(),
            content = content,
        )
    }
}

fun ColorTokens.toColorScheme(): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = surfaceTint,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    inversePrimary = inversePrimary,
    surfaceDim = surfaceDim,
    surfaceBright = surfaceBright,
    surfaceContainerLowest = surfaceContainerLowest,
    surfaceContainerLow = surfaceContainerLow,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    outline = outline,
    outlineVariant = outlineVariant,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    scrim = scrim,
)

fun ShapeTokens.toShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(small),
    small = RoundedCornerShape(small),
    medium = RoundedCornerShape(medium),
    large = RoundedCornerShape(large),
    extraLarge = RoundedCornerShape(extraLarge),
)
