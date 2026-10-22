package com.github.damontecres.wholphin.ui.avatars

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * The avatar grid (plan 4.6) in the kid-profile panel look: [title] and [subtitle] above a D-pad grid of square
 * tiles (lime border, glow and fill when focused), the current picture marked with a lime edge and check.
 * [onRemove] adds "Remove picture", [onSkip] "Skip for now"; [footer] shows below the grid (progress, errors).
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
    LaunchedEffect(avatars) {
        if (avatars.isNotEmpty()) {
            // The saved picture may be past the first rows: bring it into view, wait for that layout, then focus it
            gridState.scrollToItem(initialIndex)
            withFrameNanos {}
            firstFocus.tryRequestFocus()
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        KidTitle(title)
        if (!subtitle.isNullOrBlank()) {
            KidGap(10.dp)
            KidSubtitle(subtitle)
        }
        KidGap(12.dp)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.heightIn(max = 240.dp),
        ) {
            itemsIndexed(avatars, key = { _, it -> it.id }) { index, avatar ->
                val selected = avatar.id == current
                KidTile(
                    label = null,
                    onClick = { onPick(avatar) },
                    enabled = enabled,
                    selected = selected,
                    size = 84.dp,
                    description = avatar.id,
                    modifier = if (index == initialIndex) Modifier.focusRequester(firstFocus) else Modifier,
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
        footer()
        if (onRemove != null || onSkip != null) {
            KidGap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onRemove != null) {
                    KidButton(stringResource(R.string.remove_picture), onClick = onRemove, enabled = enabled)
                }
                if (onSkip != null) {
                    KidButton(stringResource(R.string.skip_for_now), onClick = onSkip, enabled = enabled, arrow = true)
                }
            }
        }
        KidGap(20.dp)
        OkBackHints()
    }
}

/** Change or remove [target]'s picture right away, in a dialog (chooser menu). */
@Composable
fun ProfilePictureDialog(
    target: ProfilePictureTarget,
    onDismissRequest: () -> Unit,
    viewModel: ProfilePictureViewModel = hiltViewModel(key = "picture-${target.userId}"),
) {
    LaunchedEffect(target) { viewModel.start(target) }
    KidPanelDialog(onDismissRequest = onDismissRequest, width = 750.dp) {
        ProfilePictureContent(
            viewModel = viewModel,
            onDone = onDismissRequest,
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
                    KidGap(8.dp)
                    KidSpinner(size = 28.dp, strokeWidth = 3.dp)
                }

                PictureStatus.Failed -> {
                    KidGap(8.dp)
                    KidErrorText(stringResource(R.string.picture_save_failed))
                }

                else -> {}
            }
        },
    )
}
