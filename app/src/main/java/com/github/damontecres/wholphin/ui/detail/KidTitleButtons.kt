package com.github.damontecres.wholphin.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.reportDetailActionHeight
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.neonGlowAccent
import com.github.damontecres.wholphin.ui.tryRequestFocus

/*
 * The kid title page's pieces (owner mockup kid-title-page.html, 2026-10-23): the metadata line with an age-rating badge,
 * and the button row, a big pill Play button and round Start over / Watchlist / Shuffle buttons with their labels under
 * them. Decisions are in [KidTitlePage].
 */

private val PillShape = RoundedCornerShape(percent = 50)

/** "2024 • 1h 38m • [PG]": [parts] with dots, then the rating in an outlined badge */
@Composable
fun KidMetadataLine(
    parts: List<String>,
    rating: String?,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (parts.isEmpty() && rating.isNullOrBlank()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        val style = textStyle.copy(fontWeight = FontWeight.SemiBold)
        parts.forEachIndexed { index, part ->
            if (index > 0) Text("•", style = style, color = NeonBoard.Text.copy(alpha = .55f))
            Text(part, style = style, color = NeonBoard.Text, maxLines = 1)
        }
        if (!rating.isNullOrBlank()) {
            if (parts.isNotEmpty()) Text("•", style = style, color = NeonBoard.Text.copy(alpha = .55f))
            Text(
                text = rating,
                style = style.copy(fontWeight = FontWeight.ExtraBold, fontSize = style.fontSize * .88f),
                color = NeonBoard.Text,
                maxLines = 1,
                modifier =
                    Modifier
                        .border(2.dp, NeonBoard.Text, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

/** "N seasons" for the series metadata line */
@Composable
fun kidSeasonCount(count: Int): String = pluralStringResource(R.plurals.kid_seasons, count, count)

/**
 * The kid page's buttons: the big Play button ([play], none when there's nothing to play) with its progress or next-up
 * line, then the round [actions]. Focus starts on Play (else the first round button) through [firstFocus]. A series'
 * Play button can arrive after the page shows (review round 2): it then takes the focus from the first round button,
 * but only while the focus is still in this row and no key was pressed since. Each button reports focus to
 * [onFocusChanged] (to bring the header into view).
 */
@Composable
fun KidTitleButtons(
    play: KidPlay?,
    actions: List<KidAction>,
    onWatchlist: Boolean,
    onPlay: (KidPlay) -> Unit,
    onAction: (KidAction) -> Unit,
    onFocusChanged: (FocusState) -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var rowHasFocus by remember { mutableStateOf(false) }
    var keyPressed by remember { mutableStateOf(false) }
    val playShown = play != null
    LaunchedEffect(playShown) {
        if (playShown && rowHasFocus && !keyPressed) {
            // Play is composed with the first-focus requester by now; wait for its layout, then focus it
            withFrameNanos {}
            firstFocus.tryRequestFocus()
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .reportDetailActionHeight()
                .then(modifier)
                .onFocusChanged { rowHasFocus = it.hasFocus }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown) keyPressed = true
                    false
                }.focusRestorer(firstFocus)
                .focusGroup()
                // Room for the Play button's ring and glow
                .padding(start = HeaderUtils.startPadding + 6.dp, top = 10.dp, bottom = 16.dp),
    ) {
        if (play != null) {
            KidPlayButton(
                play = play,
                onClick = { onPlay(play) },
                modifier = Modifier.focusRequester(firstFocus).onFocusChanged(onFocusChanged),
            )
        }
        actions.forEachIndexed { index, action ->
            val focus = if (play == null && index == 0) Modifier.focusRequester(firstFocus) else Modifier
            KidRoundButton(
                action = action,
                onWatchlist = onWatchlist,
                onClick = { onAction(action) },
                modifier = focus.onFocusChanged(onFocusChanged),
            )
        }
    }
}

@Composable
private fun KidPlayButton(
    play: KidPlay,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalNeonAccent.current
    val glow = neonGlowAccent(accent)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val label =
        when (val l = play.label) {
            KidPlayLabel.Play -> stringResource(R.string.play)
            KidPlayLabel.KeepWatching -> stringResource(R.string.kid_keep_watching)
            is KidPlayLabel.Episode -> stringResource(R.string.kid_play_episode, l.season, l.episode)
        }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // Focused: a white ring 3dp outside the pill
        Box(
            Modifier
                .border(3.dp, if (focused) NeonBoard.Text else Color.Transparent, PillShape)
                .padding(6.dp),
        ) {
            Surface(
                onClick = onClick,
                interactionSource = interactionSource,
                shape = ClickableSurfaceDefaults.shape(PillShape),
                colors =
                    ClickableSurfaceDefaults.colors(
                        containerColor = accent,
                        contentColor = NeonBoard.OnAccent,
                        focusedContainerColor = accent,
                        focusedContentColor = NeonBoard.OnAccent,
                        pressedContainerColor = accent,
                        pressedContentColor = NeonBoard.OnAccent,
                    ),
                glow =
                    ClickableSurfaceDefaults.glow(
                        glow = Glow(glow.copy(alpha = .4f), 16.dp),
                        focusedGlow = Glow(glow.copy(alpha = .85f), 22.dp),
                        pressedGlow = Glow(glow.copy(alpha = .85f), 22.dp),
                    ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = modifier.height(66.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxHeight().padding(start = 22.dp, end = 30.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(34.dp))
                    Text(
                        text = label.uppercase(),
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = .04.em,
                            ),
                        maxLines = 1,
                    )
                }
            }
        }
        val lineStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val progress = play.progress
        val remaining = play.remaining
        if (progress != null && remaining != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp),
            ) {
                Box(
                    Modifier
                        .width(150.dp)
                        .height(6.dp)
                        .background(NeonBoard.Text.copy(alpha = .25f), RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(accent, RoundedCornerShape(3.dp)),
                    )
                }
                val resources = LocalResources.current
                Text(
                    text = stringResource(R.string.time_left, resources.formatDuration(remaining.roundMinutes)),
                    style = lineStyle,
                    color = NeonBoard.Text.copy(alpha = .9f),
                    maxLines = 1,
                )
            }
        } else if (play.episodeTitle != null) {
            Text(
                text = stringResource(R.string.kid_next_up, play.episodeTitle),
                style = lineStyle,
                color = NeonBoard.Text.copy(alpha = .9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp).width(300.dp),
            )
        }
    }
}

@Composable
private fun KidRoundButton(
    action: KidAction,
    onWatchlist: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalNeonAccent.current
    val glow = neonGlowAccent(accent)
    val label =
        when (action) {
            KidAction.START_OVER -> stringResource(R.string.kid_start_over)
            KidAction.WATCHLIST -> stringResource(if (onWatchlist) R.string.kid_on_watchlist else R.string.kid_watchlist)
            KidAction.SHUFFLE -> stringResource(R.string.shuffle)
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        // The ring row's top: Play's ring is 6dp outside its pill
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = Color.Black.copy(alpha = .8f),
                    contentColor = NeonBoard.Text,
                    focusedContainerColor = NeonBoard.chipOn(accent),
                    focusedContentColor = accent,
                    pressedContainerColor = NeonBoard.chipOn(accent),
                    pressedContentColor = accent,
                ),
            border =
                ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(2.dp, NeonBoard.Text.copy(alpha = .85f)), shape = CircleShape),
                    focusedBorder = Border(BorderStroke(2.dp, accent), shape = CircleShape),
                    pressedBorder = Border(BorderStroke(2.dp, accent), shape = CircleShape),
                ),
            glow =
                ClickableSurfaceDefaults.glow(
                    focusedGlow = Glow(glow.copy(alpha = .85f), 10.dp),
                    pressedGlow = Glow(glow.copy(alpha = .85f), 10.dp),
                ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = modifier.size(66.dp),
        ) {
            Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                when (action) {
                    KidAction.START_OVER -> {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp).graphicsLayer { scaleX = -1f },
                        )
                    }

                    KidAction.WATCHLIST -> {
                        Icon(
                            imageVector = if (onWatchlist) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (onWatchlist) Color.Red else LocalContentColor.current,
                            modifier = Modifier.size(30.dp),
                        )
                    }

                    KidAction.SHUFFLE -> {
                        Text(
                            text = stringResource(R.string.fa_shuffle),
                            fontFamily = FontAwesome,
                            fontSize = 24.sp,
                        )
                    }
                }
            }
        }
        Text(
            text = label.uppercase(),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = .05.em,
                ),
            color = NeonBoard.Text,
            maxLines = 1,
        )
    }
}
