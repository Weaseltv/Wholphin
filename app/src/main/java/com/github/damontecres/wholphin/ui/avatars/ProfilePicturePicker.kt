package com.github.damontecres.wholphin.ui.avatars

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * The avatar grid (plan 4.6): D-pad grid, the current picture marked, the profile's name above.
 * [onRemove] adds "Remove picture"; [footer] shows below the grid (progress, errors).
 */
@Composable
fun ProfilePicturePicker(
    name: String?,
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
    LaunchedEffect(avatars) { if (avatars.isNotEmpty()) firstFocus.tryRequestFocus() }
    val neon = isWeaselTv()
    val tileShape = if (neon) RectangleShape else CircleShape
    val initialIndex = avatars.indexOfFirst { it.id == current }.coerceAtLeast(0)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        if (!name.isNullOrBlank()) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(R.string.pick_a_picture),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.heightIn(max = 340.dp),
        ) {
            itemsIndexed(avatars, key = { _, it -> it.id }) { index, avatar ->
                val selected = avatar.id == current
                Surface(
                    onClick = { onPick(avatar) },
                    enabled = enabled,
                    modifier =
                        Modifier
                            .size(96.dp)
                            .then(if (index == initialIndex) Modifier.focusRequester(firstFocus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(shape = tileShape),
                    border =
                        ClickableSurfaceDefaults.border(
                            border =
                                if (selected) {
                                    Border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), shape = tileShape)
                                } else {
                                    Border.None
                                },
                            focusedBorder =
                                Border(
                                    BorderStroke(3.dp, if (neon) NeonBoard.Volt else MaterialTheme.colorScheme.primary),
                                    shape = tileShape,
                                ),
                        ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = avatar.model,
                            contentDescription = avatar.id,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(tileShape),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.current_picture),
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(28.dp),
                            )
                        }
                    }
                }
            }
        }
        footer()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onRemove != null) {
                TextButton(stringRes = R.string.remove_picture, onClick = onRemove, enabled = enabled)
            }
            if (onSkip != null) {
                TextButton(stringRes = R.string.skip_for_now, onClick = onSkip, enabled = enabled)
            }
        }
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
    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ProfilePictureContent(
            viewModel = viewModel,
            onDone = onDismissRequest,
            modifier = Modifier.padding(24.dp).fillMaxWidth(.6f),
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
        ProfilePictureContent(
            viewModel = viewModel,
            onDone = onDone,
            modifier = Modifier.fillMaxWidth(.6f),
        )
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
        name = state.target?.name,
        avatars = state.avatars,
        current = state.current,
        enabled = state.status != PictureStatus.Saving,
        onPick = viewModel::pick,
        onRemove = viewModel::remove,
        modifier = modifier,
        footer = {
            when (state.status) {
                PictureStatus.Saving -> {
                    CircularProgress(Modifier.size(24.dp))
                }

                PictureStatus.Failed -> {
                    Text(
                        text = stringResource(R.string.picture_save_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {}
            }
        },
    )
}
