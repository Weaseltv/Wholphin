package com.github.damontecres.wholphin.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.enableMarquee
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonAccentFor
import com.github.damontecres.wholphin.ui.theme.neonCardBorder
import com.github.damontecres.wholphin.ui.theme.neonCardGlow
import com.github.damontecres.wholphin.ui.theme.neonCardScale
import com.github.damontecres.wholphin.ui.theme.neonCardShape
import kotlinx.coroutines.delay

/**
 * Card for use in [com.github.damontecres.wholphin.ui.detail.CardGrid]
 */
@Composable
fun GridCard(
    item: BaseItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    accent: Color = neonAccentFor(item),
    imageAspectRatio: Float = AspectRatios.TALL,
    imageContentScale: ContentScale = ContentScale.Fit,
    imageType: ViewOptionImageType = ViewOptionImageType.PRIMARY,
    showTitle: Boolean = true,
    fillWidth: Int? = null,
    fillHeight: Int? = null,
) {
    val dto = item?.data
    val focused by interactionSource.collectIsFocusedAsState()
    var focusedAfterDelay by remember { mutableStateOf(false) }

    val hideOverlayDelay = 500L
    if (focused) {
        LaunchedEffect(Unit) {
            delay(hideOverlayDelay)
            if (focused) {
                focusedAfterDelay = true
            } else {
                focusedAfterDelay = false
            }
        }
    } else {
        focusedAfterDelay = false
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().semantics { item?.title?.let { contentDescription = it } },
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            colors =
                CardDefaults.colors(
                    containerColor = Color.Transparent,
                ),
            shape = neonCardShape(),
            scale = neonCardScale(),
            border = neonCardBorder(accent),
            glow = neonCardGlow(accent),
        ) {
            ItemCardImage(
                item = item,
                imageType = imageType.imageType,
                name = item?.name,
//                        showOverlay = !focusedAfterDelay,
                showOverlay = true,
                favorite = dto?.userData?.isFavorite ?: false,
                watched = dto?.userData?.played ?: false,
                unwatchedCount = item?.countBadge ?: -1,
                watchedPercent = dto?.userData?.playedPercentage,
                numberOfVersions = dto?.mediaSourceCount ?: 0,
                useFallbackText = false,
                contentScale = imageContentScale,
                fillWidth = fillWidth,
                fillHeight = fillHeight,
                accent = accent,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(imageAspectRatio.coerceAtLeast(AspectRatios.MIN))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        AnimatedVisibility(showTitle) {
            SlidingCardText(focused) {
                PosterCardTitle(item?.title, focused)
                PosterCardSubtitle(item?.subtitle)
            }
        }
    }
}

/**
 * The height of a [GridCard] at [width] with a [titleLines]-line title and a subtitle. Known
 * before any items load and independent of their titles.
 */
@Composable
fun gridCardHeight(
    width: Dp,
    imageAspectRatio: Float,
    showTitle: Boolean,
    titleLines: Int = 2,
): Dp {
    val imageHeight = width / imageAspectRatio.coerceAtLeast(AspectRatios.MIN)
    if (!showTitle) return imageHeight
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val titleStyle =
        (if (isWeaselTv()) NeonType.cardTitle() else MaterialTheme.typography.bodyMedium)
            .copy(fontWeight = FontWeight.SemiBold)
    val subtitleStyle = if (isWeaselTv()) NeonType.cardSubtitle() else MaterialTheme.typography.bodySmall
    val captionHeight =
        remember(density, measurer, titleStyle, subtitleStyle, width, titleLines) {
            with(density) {
                // Matches PosterCardTitle/PosterCardSubtitle's horizontal padding
                val constraints = Constraints(maxWidth = (width - 8.dp).roundToPx().coerceAtLeast(0))
                val title =
                    measurer.measure(
                        List(titleLines) { "A" }.joinToString("\n"),
                        titleStyle,
                        maxLines = titleLines,
                        constraints = constraints,
                    )
                val subtitle = measurer.measure("A", subtitleStyle, maxLines = 1, constraints = constraints)
                (title.size.height + subtitle.size.height).toDp()
            }
        }
    // Column spacing (4dp) + SlidingCardText's total padding (12dp)
    return imageHeight + 4.dp + 12.dp + captionHeight
}
