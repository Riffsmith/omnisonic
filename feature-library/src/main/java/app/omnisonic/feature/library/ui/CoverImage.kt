package app.omnisonic.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.OmnisonicTheme
import coil3.compose.AsyncImage

/**
 * Cover slot with a token-sized placeholder under the image: a missing or
 * failed cover simply reveals the placeholder layer (UI_SPEC.md §3.4
 * imagery rules — sizing and placeholder alpha come from tokens only).
 * The request size follows the measured cell (D-17), so grids never pull
 * original-size art.
 */
@Composable
fun CoverImage(
    cover: CoverImageKey?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = OmnisonicTheme.tokens.shapes.cover,
    placeholderIcon: ImageVector = Icons.Filled.MusicNote,
) {
    val tokens = OmnisonicTheme.tokens
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(tokens.colors.coverPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val sizePx = CoverSizing.sizePx(
            widthPx = if (maxWidth.value.isFinite()) with(density) { maxWidth.roundToPx() } else 0,
            heightPx = if (maxHeight.value.isFinite()) with(density) { maxHeight.roundToPx() } else 0,
        )
        Icon(
            imageVector = placeholderIcon,
            contentDescription = null,
            tint = tokens.colors.onCoverPlaceholder.copy(alpha = tokens.imagery.placeholderTintAlpha),
        )
        if (cover != null) {
            AsyncImage(
                model = sizePx?.let { cover.copy(sizePx = it) } ?: cover,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
