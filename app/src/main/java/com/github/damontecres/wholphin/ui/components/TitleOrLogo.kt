package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.logCoilError
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

@Composable
fun TitleOrLogo(
    title: String?,
    logoImageUrl: String?,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var imageError by remember(logoImageUrl) { mutableStateOf(false) }
    val detailCompact = LocalCompactDetailHeader.current
    val logoHeight =
        if (compact) {
            28.dp
        } else if (detailCompact) {
            44.dp
        } else {
            HeaderUtils.logoHeight
        }
    Box(
        modifier = modifier,
    ) {
        if (showLogo && logoImageUrl != null && !imageError) {
            AsyncImage(
                model = logoImageUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                onError = {
                    logCoilError(logoImageUrl, it.result)
                    imageError = true
                },
                modifier =
                    Modifier
                        .height(logoHeight)
                        .widthIn(max = 320.dp),
            )
        } else {
            Title(title, Modifier, compact)
        }
    }
}

@Composable
private fun Title(
    title: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // Use the bundled display face for text when the server has no usable logo.
    val neon = isWeaselTv()
    val heading = (title ?: "").let { if (neon) it.uppercase() else it }
    val style =
        if (neon) {
            NeonType.hero(
                if (compact) {
                    20.sp
                } else if (LocalCompactDetailHeader.current) {
                    32.sp
                } else {
                    48.sp
                },
            )
        } else {
            MaterialTheme.typography.headlineMedium
        }
    val maxLines = if (compact) 1 else 2
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val fitted =
            remember(heading, style, maxLines, constraints, measurer, neon) {
                if (!neon) {
                    style
                } else {
                    (style.fontSize.value.toInt() downTo 18)
                        .map { style.copy(fontSize = it.sp, lineHeight = (it * 1.25f).sp) }
                        .firstOrNull {
                            val layout = measurer.measure(heading, it, maxLines = maxLines, constraints = constraints)
                            !layout.hasVisualOverflow && layout.multiParagraph.minIntrinsicWidth <= constraints.maxWidth
                        } ?: style.copy(fontSize = 18.sp, lineHeight = 22.5.sp)
                }
            }
        Text(
            text = heading,
            color = MaterialTheme.colorScheme.onSurface,
            style = fitted,
            fontWeight = if (neon) null else FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TitleOrLogo(
    item: BaseItem?,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    val logoImageUrl = rememberLogoUrl(item)
    TitleOrLogo(
        title = item?.title,
        logoImageUrl = logoImageUrl,
        showLogo = showLogo,
        modifier = modifier,
    )
}

@Composable
fun rememberLogoUrl(item: BaseItem?): String? {
    val imageUrlService = LocalImageUrlService.current
    return remember(item?.id) {
        if (item?.type == BaseItemKind.EPISODE && item.data.seriesId != null && item.data.parentLogoImageTag != null) {
            imageUrlService.getItemImageUrl(item.data.seriesId!!, ImageType.LOGO)
        } else if (ImageType.LOGO in item?.data?.imageTags.orEmpty()) {
            imageUrlService.getItemImageUrl(item, ImageType.LOGO)
        } else {
            null
        }
    }
}

@Composable
fun SimpleTitleOrLogo(
    item: BaseItem?,
    showLogo: Boolean,
    modifier: Modifier = Modifier,
) {
    val logoImageUrl = rememberLogoUrl(item)
    var imageError by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        if (showLogo && logoImageUrl != null && !imageError) {
            AsyncImage(
                model = logoImageUrl,
                contentDescription = item?.title,
                contentScale = ContentScale.Fit,
                onError = {
                    logCoilError(logoImageUrl, it.result)
                    imageError = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Title(item?.title, Modifier)
        }
    }
}
