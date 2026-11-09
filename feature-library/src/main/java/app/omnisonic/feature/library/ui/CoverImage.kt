package app.omnisonic.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.Dp
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.OmnisonicTheme
import coil3.compose.AsyncImage

/**
 * Cover slot with a token-sized placeholder under the image: a missing or
 * failed cover simply reveals the placeholder layer (UI_SPEC.md §3.4
 * imagery rules — sizing and placeholder alpha come from tokens only).
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(tokens.colors.coverPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = placeholderIcon,
            contentDescription = null,
            tint = tokens.colors.onCoverPlaceholder.copy(alpha = tokens.imagery.placeholderTintAlpha),
        )
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
