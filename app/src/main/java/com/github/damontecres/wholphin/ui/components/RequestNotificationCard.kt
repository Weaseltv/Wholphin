package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.services.RequestStageKind
import com.github.damontecres.wholphin.services.RequestStatusItem
import com.github.damontecres.wholphin.ui.theme.NeonSectionPalette

/** Owner's poster-led TV popup. The countdown is read during drawing, without recomposing the card. */
@Composable
fun RequestNotificationCard(
    rows: List<RequestStatusItem>,
    eventCount: Int,
    canWatch: Boolean,
    firstFocus: FocusRequester,
    remaining: () -> Float,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = NeonSectionPalette.Requests.glow
    val edge = NeonSectionPalette.Requests.border
    val single = rows.singleOrNull().takeIf { eventCount == 1 }
    val cover = single ?: rows.firstOrNull { it.item?.posterUrl != null } ?: rows.firstOrNull()
    val allReady = rows.size == eventCount && rows.all { it.stage.kind == RequestStageKind.READY }
    val failed = single?.stage?.kind == RequestStageKind.FAILED
    val badgeColor = if (failed) Color(0xFFFFB000) else accent
    val status = single?.stage?.label ?: if (allReady) "Ready to watch" else "Request updates"
    val title = single?.title ?: if (allReady) "$eventCount requests ready" else "$eventCount request updates"
    val primary = when { eventCount > 1 -> "View requests"; canWatch -> "Watch now"; else -> "Details" }
    val instruction = when {
        eventCount > 1 -> "Press OK to view your request updates."
        single?.stage?.kind == RequestStageKind.READY && canWatch -> "Your request is ready. Press OK to start."
        single?.stage?.kind == RequestStageKind.PARTIAL && canWatch -> "Some episodes are ready. Press OK to watch."
        else -> "Your request has an update. Press OK for details."
    }
    val context = LocalContext.current
    val orbitron = remember(context) {
        context.resources.getIdentifier("orbitron_extrabold", "font", context.packageName)
            .takeIf { it != 0 }?.let { FontFamily(Font(it, FontWeight.ExtraBold)) }
    }
    val display = MaterialTheme.typography.titleLarge.copy(
        fontFamily = orbitron ?: MaterialTheme.typography.titleLarge.fontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = .02.em,
    )
    val cardShape = RoundedCornerShape(13.dp)
    val buttonShape = RoundedCornerShape(9.dp)
    val focusRing = Border(BorderStroke(2.dp, Color.White), shape = buttonShape)
    val quietRing = Border(BorderStroke(1.dp, Color(0xFF566366)), shape = buttonShape)

    Column(
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .drawBehind {
                // Layer faint rounded strokes outside the card for a soft Requests-teal halo.
                for (ring in 14 downTo 1) {
                    val spread = ring.dp.toPx()
                    drawRoundRect(
                        color = accent.copy(alpha = .025f + (14 - ring) * .002f),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(13.dp.toPx() + spread),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            .border(1.dp, edge, cardShape)
            .clip(cardShape)
            .background(Brush.linearGradient(listOf(Color(0xFF0B3030), Color(0xFF071013), Color(0xFF071013)))),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(66.dp, 100.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF142528))
                    .border(1.dp, Color(0xFF627371), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(cover?.title?.take(1)?.uppercase() ?: "W", style = display, color = accent.copy(alpha = .4f))
                AsyncImage(
                    model = cover?.item?.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                if (eventCount > 1) {
                    Text(
                        "+${eventCount - 1}", color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color(0xD9071013), RoundedCornerShape(topStart = 5.dp))
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(
                    modifier = Modifier.background(badgeColor.copy(alpha = .08f), RoundedCornerShape(50))
                        .border(.8.dp, badgeColor, RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        if (single?.stage?.ready == true || allReady) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null, tint = badgeColor, modifier = Modifier.size(11.dp),
                    )
                    Text(
                        status.uppercase(), color = badgeColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = .1.em,
                        ),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        buildAnnotatedString {
                            append(title.uppercase())
                            single?.item?.releaseDate?.year?.let {
                                withStyle(SpanStyle(color = accent, fontSize = 12.sp)) { append(" $it") }
                            }
                            if (single?.request?.is4k == true) {
                                withStyle(SpanStyle(color = accent, fontSize = 10.sp)) { append(" · 4K") }
                            }
                        },
                        style = display, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        instruction, color = Color(0xFFB5C5C8),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onPrimary,
                        modifier = Modifier.weight(1f, fill = false).widthIn(min = 122.dp).focusRequester(firstFocus),
                        shape = ClickableSurfaceDefaults.shape(buttonShape),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = accent, contentColor = Color(0xFF001B1B),
                            focusedContainerColor = accent, focusedContentColor = Color(0xFF001B1B),
                            pressedContainerColor = accent, pressedContentColor = Color(0xFF001B1B),
                        ),
                        border = ClickableSurfaceDefaults.border(focusedBorder = focusRing, pressedBorder = focusRing),
                        glow = ClickableSurfaceDefaults.glow(
                            focusedGlow = Glow(accent.copy(alpha = .45f), 14.dp),
                            pressedGlow = Glow(accent.copy(alpha = .45f), 14.dp),
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp), contentHeight = 32.dp,
                    ) {
                        if (canWatch) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(primary.uppercase(), style = display.copy(fontSize = 11.sp, lineHeight = 14.sp), maxLines = 1)
                    }
                    Button(
                        onClick = onDismiss, modifier = Modifier.width(76.dp),
                        shape = ClickableSurfaceDefaults.shape(buttonShape),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF182426), contentColor = Color(0xFFDCE5E6),
                            focusedContainerColor = Color(0xFF203638), focusedContentColor = Color.White,
                        ),
                        border = ClickableSurfaceDefaults.border(border = quietRing, focusedBorder = focusRing, pressedBorder = focusRing),
                        glow = ClickableSurfaceDefaults.glow(),
                        contentPadding = PaddingValues(horizontal = 8.dp), contentHeight = 32.dp,
                    ) {
                        Text("Dismiss", style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 15.sp))
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(3.dp).background(accent.copy(alpha = .12f))) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(accent, Color(0xFFCCF43B)), endX = size.width * remaining().coerceAtLeast(.001f)),
                size = Size(size.width * remaining().coerceIn(0f, 1f), size.height),
            )
        }
    }
}
