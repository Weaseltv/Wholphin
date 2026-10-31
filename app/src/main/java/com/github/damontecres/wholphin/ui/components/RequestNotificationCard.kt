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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.services.RequestStageKind
import com.github.damontecres.wholphin.services.RequestStatusItem
import com.github.damontecres.wholphin.ui.theme.NeonFonts
import com.github.damontecres.wholphin.ui.theme.NeonSectionPalette

/** Flat, square Requests card shared by readiness and noninteractive submission feedback. */
@Composable
fun RequestNotificationCard(
    rows: List<RequestStatusItem>,
    eventCount: Int,
    canWatch: Boolean,
    firstFocus: FocusRequester?,
    remaining: () -> Float,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmationLabel: String? = null,
) {
    val accent = NeonSectionPalette.Requests.glow
    val edge = NeonSectionPalette.Requests.border
    val cardColor = Color(0xFF0B0E13)
    val textColor = Color(0xFFE4E7EF)
    val muted = Color(0xFF828A9B)
    val single = rows.singleOrNull().takeIf { eventCount == 1 }
    val cover = single ?: rows.firstOrNull { it.item?.posterUrl != null } ?: rows.firstOrNull()
    val allReady = rows.size == eventCount && rows.all { it.stage.kind == RequestStageKind.READY }
    val confirmation = confirmationLabel != null
    val status = confirmationLabel ?: single?.stage?.label ?: if (allReady) "Ready to watch" else "Request updates"
    val title = single?.title ?: if (allReady) "$eventCount requests ready" else "$eventCount request updates"
    val primary = when { eventCount > 1 -> "View requests"; canWatch -> "Watch now"; else -> "Details" }
    val instruction = when {
        confirmation -> "We'll let you know when it's ready to watch."
        eventCount > 1 -> "Your requests have updates."
        single?.stage?.kind == RequestStageKind.READY -> "Your request is ready."
        single?.stage?.kind == RequestStageKind.PARTIAL -> "Some episodes are ready."
        else -> "Your request has an update."
    }
    val metadata = single?.let { row ->
        listOfNotNull(
            row.item?.releaseDate?.year?.toString(),
            if (row.request.type == "tv") "TV series" else "Movie",
            "4K".takeIf { row.request.is4k == true },
        ).joinToString(" · ")
    }
    val context = LocalContext.current
    val (bodyFont, titleFont) = remember(context) { NeonFonts.families(context) }
    val body = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = bodyFont, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp,
    )
    val display = MaterialTheme.typography.titleLarge.copy(
        fontFamily = titleFont, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = .02.em,
    )
    val label = body.copy(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = .08.em)
    val focusRing = Border(BorderStroke(1.dp, textColor), inset = 2.dp, shape = RectangleShape)
    val quietRing = Border(BorderStroke(Dp.Hairline, Color(0xFF29313D)), shape = RectangleShape)
    val watchGlow = if (canWatch) Glow(accent.copy(alpha = .35f), 8.dp) else Glow.None

    Column(
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(RectangleShape)
            .background(cardColor)
            .border(Dp.Hairline, edge, RectangleShape)
            .drawBehind {
                drawRect(color = accent, size = Size(3.dp.toPx(), size.height))
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 13.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.size(56.dp, 84.dp)
                    .clip(RectangleShape)
                    .background(Color(0xFF142528))
                    .border(Dp.Hairline, Color(0xFF29313D), RectangleShape),
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
                        "+${eventCount - 1}", color = textColor, style = label,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color(0xE60B0E13))
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    status.uppercase(), color = accent,
                    style = label.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = .16.em),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(title.uppercase(), style = display, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                metadata?.let { Text(it, style = body.copy(fontSize = 10.sp, lineHeight = 13.sp), color = muted) }
                Text(instruction, style = body, color = textColor)
                if (!confirmation && firstFocus != null) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onPrimary,
                            modifier = Modifier.weight(1f, fill = false).widthIn(min = 100.dp).focusRequester(firstFocus),
                            shape = ClickableSurfaceDefaults.shape(RectangleShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = accent, contentColor = Color(0xFF071519),
                                focusedContainerColor = accent, focusedContentColor = Color(0xFF071519),
                                pressedContainerColor = accent, pressedContentColor = Color(0xFF071519),
                            ),
                            border = ClickableSurfaceDefaults.border(focusedBorder = focusRing, pressedBorder = focusRing),
                            glow = ClickableSurfaceDefaults.glow(focusedGlow = watchGlow, pressedGlow = watchGlow),
                            contentPadding = PaddingValues(horizontal = 12.dp), contentHeight = 25.dp,
                        ) {
                            Text(primary.uppercase(), style = label, maxLines = 1)
                        }
                        Button(
                            onClick = onDismiss, modifier = Modifier.width(66.dp),
                            shape = ClickableSurfaceDefaults.shape(RectangleShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = cardColor, contentColor = textColor,
                                focusedContainerColor = cardColor, focusedContentColor = textColor,
                            ),
                            border = ClickableSurfaceDefaults.border(border = quietRing, focusedBorder = focusRing, pressedBorder = focusRing),
                            glow = ClickableSurfaceDefaults.glow(),
                            contentPadding = PaddingValues(horizontal = 8.dp), contentHeight = 25.dp,
                        ) {
                            Text("DISMISS", style = label, maxLines = 1)
                        }
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = .15f))) {
            drawRect(color = accent, size = Size(size.width * remaining().coerceIn(0f, 1f), size.height))
        }
    }
}
