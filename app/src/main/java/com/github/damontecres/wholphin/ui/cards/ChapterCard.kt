package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.ui.AppColors
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.roundSeconds
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.neonCardBorder
import com.github.damontecres.wholphin.ui.theme.neonCardGlow
import com.github.damontecres.wholphin.ui.theme.neonCardScale
import com.github.damontecres.wholphin.ui.theme.neonCardShape
import org.jellyfin.sdk.model.api.ImageType

/**
 * Card for a [com.github.damontecres.wholphin.data.model.Chapter]
 */
@Composable
fun ChapterCard(
    chapter: Chapter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardHeight: Dp = 120.dp,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    accent: Color = LocalNeonAccent.current,
) {
    val density = LocalDensity.current
    val imageUrlService = LocalImageUrlService.current
    val imageUrl =
        remember(chapter, cardHeight, density) {
            imageUrlService.getItemImageUrl(
                itemId = chapter.itemId,
                imageType = ImageType.CHAPTER,
                tag = chapter.tag,
                imageIndex = chapter.index,
                fillHeight = with(density) { cardHeight.roundToPx() },
            )
        }
    Card(
        modifier = modifier.height(cardHeight).width(AspectRatios.WIDE * cardHeight),
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        shape = neonCardShape(),
        scale = neonCardScale(),
        border = neonCardBorder(accent),
        glow = neonCardGlow(accent),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(AppColors.TransparentBlack50),
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    chapter.name?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val resources = LocalResources.current
                    val positionText =
                        remember(chapter.position) { resources.formatDuration(chapter.position.roundSeconds) }
                    Text(
                        text = positionText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
    }
}
