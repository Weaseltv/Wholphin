package com.github.damontecres.wholphin.ui.avatars

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.ui.kids.KidButton
import com.github.damontecres.wholphin.ui.kids.KidColors
import com.github.damontecres.wholphin.ui.kids.KidErrorText
import com.github.damontecres.wholphin.ui.kids.KidGap
import com.github.damontecres.wholphin.ui.kids.KidPanel
import com.github.damontecres.wholphin.ui.kids.KidPanelDialog
import com.github.damontecres.wholphin.ui.kids.KidSpinner
import com.github.damontecres.wholphin.ui.kids.KidSubtitle
import com.github.damontecres.wholphin.ui.kids.KidTile
import com.github.damontecres.wholphin.ui.kids.KidTitle
import com.github.damontecres.wholphin.ui.kids.OkBackHints
import com.github.damontecres.wholphin.ui.setup.FirstAvatarStatus
import com.github.damontecres.wholphin.ui.setup.PendingAvatar
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * The avatar grid (plan 4.6) in the kid-profile panel look: [title] and [subtitle] above a D-pad grid of square
 * tiles (lime border, glow and fill when focused), the current picture marked with a lime edge and check.
 *
 * Owner mockup (2026-10-22): the grid shows [VISIBLE_ROWS] full rows on a 1080p TV (the rest scroll), and the bottom row
 * holds the OK / BACK hints on the left with the buttons on the right: [onRemove] adds "Remove picture", [onSkip]
 * "Skip for now". [footer] (progress, errors) sits between them, so it never pushes the grid off the panel.
 */
@Composable
fun ProfilePicturePicker(
    title: String,
    subtitle: String?,
    avatars: List<Avatar>,
    current: String?,
    onPick: (Avatar) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onRemove: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    val firstFocus = remember { FocusRequester() }
    val initialIndex = avatars.indexOfFirst { it.id == current }.coerceAtLeast(0)
    val gridState = rememberLazyGridState()
    // The tile this picker focused itself, and whether the user has moved since: a current avatar found later moves the
    // focus to it only until then, never away from where the user went (review: any key in the picker, or focus on any
    // other tile, counts, so a move straight to "Remove picture" does too)
    var focusedByPicker by remember(avatars) { mutableIntStateOf(-1) }
    var settled by remember(avatars) { mutableStateOf(false) }
    var userMoved by remember(avatars) { mutableStateOf(false) }
    LaunchedEffect(avatars, initialIndex) {
        if (avatars.isNotEmpty() && !userMoved) {
            // The saved picture may be past the first rows: bring it into view, wait for that layout, then focus it
            focusedByPicker = initialIndex
            gridState.scrollToItem(initialIndex)
            withFrameNanos {}
            firstFocus.tryRequestFocus()
            settled = true
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier.onPreviewKeyEvent {
                if (settled && it.type == KeyEventType.KeyDown) userMoved = true
                false
            },
    ) {
        KidTitle(title)
        if (!subtitle.isNullOrBlank()) {
            KidGap(10.dp)
            KidSubtitle(subtitle)
        }
        KidGap(10.dp)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(COLUMNS),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            // Room for the focused tile's scale and glow
            contentPadding = PaddingValues(GRID_PADDING),
            modifier = Modifier.height(TILE * VISIBLE_ROWS + TILE_GAP * (VISIBLE_ROWS - 1) + GRID_PADDING * 2),
        ) {
            itemsIndexed(avatars, key = { _, it -> it.id }) { index, avatar ->
                val selected = avatar.id == current
                KidTile(
                    label = null,
                    onClick = { onPick(avatar) },
                    enabled = enabled,
                    selected = selected,
                    size = TILE,
                    description = avatar.id,
                    modifier =
                        Modifier
                            .onFocusChanged { if (it.isFocused && settled && index != focusedByPicker) userMoved = true }
                            .then(if (index == initialIndex) Modifier.focusRequester(firstFocus) else Modifier),
                ) {
                    AsyncImage(
                        model = avatar.model,
                        contentDescription = avatar.id,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().padding(if (selected) 2.dp else 0.dp),
                    )
                    if (selected) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(KidColors.Lime, CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.current_picture),
                                tint = NeonBoard.OnAccent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        KidGap(12.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        ) {
            OkBackHints()
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) { footer() }
            if (onRemove != null) {
                KidButton(stringResource(R.string.remove_picture), onClick = onRemove, enabled = enabled)
            }
            if (onSkip != null) {
                KidButton(stringResource(R.string.skip_for_now), onClick = onSkip, enabled = enabled, arrow = true)
            }
        }
    }
}

/** Grid columns; 48 avatars make eight rows, of which [VISIBLE_ROWS] show */
private const val COLUMNS = 6

/** Rows shown in full, with the panel's title, subtitle and bottom row, inside a 1080p TV's 540dp */
private const val VISIBLE_ROWS = 3
private val TILE = 76.dp
private val TILE_GAP = 14.dp
private val GRID_PADDING = 12.dp

/**
 * Change or remove [target]'s picture right away, in a dialog (chooser menu). [mayChange] runs right before every save or
 * remove; false refuses it and closes the dialog with no change (review round 2).
 */
@Composable
fun ProfilePictureDialog(
    target: ProfilePictureTarget,
    onDismissRequest: () -> Unit,
    mayChange: (suspend () -> Boolean)? = null,
    viewModel: ProfilePictureViewModel = hiltViewModel(key = "picture-${target.userId}"),
) {
    LaunchedEffect(target) { viewModel.start(target, mayChange) }
    DisposableEffect(target) { onDispose { viewModel.stop() } }
    KidPanelDialog(onDismissRequest = onDismissRequest, width = 750.dp) {
        ProfilePictureContent(
            viewModel = viewModel,
            onDone = onDismissRequest,
        )
    }
}

/**
 * An adult's first sign-in on a TV without a picture (contract 11.1): they choose one from the adult list before going
 * on. There is no skip; Back leaves the sign-in. If the upload fails it says so and offers "Try again" and "Continue
 * without a picture".
 */
@Composable
fun FirstAvatarDialog(
    pending: PendingAvatar,
    avatars: List<Avatar>,
    onPick: (Avatar) -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val name = pending.target.name.orEmpty()
    val title = if (name.isBlank()) stringResource(R.string.pick_a_picture) else stringResource(R.string.first_avatar_title, name)
    KidPanelDialog(onDismissRequest = onDismissRequest, width = 750.dp) {
        if (pending.status == FirstAvatarStatus.FAILED) {
            KidTitle(title)
            KidGap(14.dp)
            KidErrorText(stringResource(R.string.first_avatar_failed))
            KidGap(28.dp)
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { retryFocus.tryRequestFocus() }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KidButton(stringResource(R.string.try_again), onClick = onRetry, modifier = Modifier.focusRequester(retryFocus))
                KidButton(stringResource(R.string.continue_without_picture), onClick = onContinue, arrow = true)
            }
            KidGap(24.dp)
            OkBackHints()
            return@KidPanelDialog
        }
        ProfilePicturePicker(
            title = title,
            subtitle = stringResource(R.string.first_avatar_subtitle),
            avatars = avatars,
            current = pending.chosen?.id,
            enabled = pending.status != FirstAvatarStatus.SAVING,
            onPick = onPick,
            footer = {
                if (pending.status == FirstAvatarStatus.SAVING) KidSpinner(size = 28.dp, strokeWidth = 3.dp)
            },
        )
    }
}

/** Settings: the signed-in profile's picture, as a full page. */
@Composable
fun ProfilePicturePage(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilePictureViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.startForCurrentUser() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        KidPanel(Modifier.width(750.dp)) {
            ProfilePictureContent(
                viewModel = viewModel,
                onDone = onDone,
            )
        }
    }
}

@Composable
private fun ProfilePictureContent(
    viewModel: ProfilePictureViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val savedMessage = stringResource(R.string.picture_saved)
    LaunchedEffect(state.status) {
        if (state.status == PictureStatus.Saved) {
            Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearResult()
            onDone()
        } else if (state.status == PictureStatus.Refused) {
            viewModel.clearResult()
            onDone()
        }
    }
    ProfilePicturePicker(
        title = stringResource(R.string.change_picture),
        subtitle = state.target?.name?.let { stringResource(R.string.pick_a_picture_for, it) },
        avatars = state.avatars,
        current = state.current,
        enabled = state.status != PictureStatus.Saving,
        onPick = viewModel::pick,
        onRemove = viewModel::remove,
        modifier = modifier,
        footer = {
            when (state.status) {
                PictureStatus.Saving -> {
                    KidSpinner(size = 28.dp, strokeWidth = 3.dp)
                }

                PictureStatus.Failed -> {
                    KidErrorText(stringResource(R.string.picture_save_failed), maxLines = 2)
                }

                else -> {}
            }
        },
    )
}
