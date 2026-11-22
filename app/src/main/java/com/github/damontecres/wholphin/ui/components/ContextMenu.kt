package com.github.damontecres.wholphin.ui.components

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

@Composable
fun ContextMenuDialog(
    onDismissRequest: () -> Unit,
    contextMenu: ContextMenu,
    getMediaSource: ((dto: BaseItemDto, itemPlayback: ItemPlayback?) -> MediaSourceInfo?)?,
    preferredSubtitleLanguage: String?,
) {
    when (contextMenu) {
        is ContextMenu.ForBaseItem -> {
            ContextMenu(
                onDismissRequest,
                contextMenu,
                getMediaSource,
                preferredSubtitleLanguage,
                contextMenu.actions,
            )
        }

        is ContextMenu.ForPerson -> {
            ContextMenu(
                onDismissRequest = onDismissRequest,
                item = contextMenu,
                actions = contextMenu.actions,
            )
        }
    }
}

@Composable
fun ContextMenu(
    onDismissRequest: () -> Unit,
    contextMenu: ContextMenu.ForBaseItem,
    getMediaSource: ((dto: BaseItemDto, itemPlayback: ItemPlayback?) -> MediaSourceInfo?)?,
    preferredSubtitleLanguage: String?,
    actions: ContextMenuActions,
) {
    val item = contextMenu.item
    val chosenStreams = contextMenu.chosenStreams
    val resources = LocalResources.current
    var chooseVersion by remember { mutableStateOf<DialogParams?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPlayWithDialog by remember { mutableStateOf(false) }

    val dialogItems =
        remember(resources, item, chosenStreams) {
            buildContextMenuItems(
                resources = resources,
                item = item,
                watched = item.played,
                favorite = item.favorite,
                seriesId = item.data.seriesId,
                sourceId = chosenStreams?.source?.id?.toUUIDOrNull(),
                canClearChosenStreams = chosenStreams.let { it?.itemPlayback != null || it?.plc != null },
                contextMenu = contextMenu,
                actions = actions,
                onChooseVersion = {
                    chooseVersion =
                        chooseVersionParams(
                            resources,
                            item.data.mediaSources.orEmpty(),
                            chosenStreams?.source?.id?.toUUIDOrNull(),
                        ) { idx ->
                            val source = item.data.mediaSources!![idx]
                            actions.onChooseVersion.invoke(item, source)
                            onDismissRequest.invoke()
                        }
                },
                onChooseTracks = { type ->
                    getMediaSource
                        ?.invoke(
                            item.data,
                            chosenStreams?.itemPlayback,
                        )?.let { source ->
                            chooseVersion =
                                chooseStream(
                                    resources = resources,
                                    streams = source.mediaStreams.orEmpty(),
                                    type = type,
                                    currentIndex =
                                        if (type == MediaStreamType.AUDIO) {
                                            chosenStreams?.audioStream?.index
                                        } else {
                                            chosenStreams?.subtitleStream?.index
                                        },
                                    onClick = { trackIndex ->
                                        actions.onChooseTracks.invoke(
                                            ChosenTrackResult(
                                                item = item,
                                                streamType = type,
                                                trackIndex = trackIndex,
                                                itemPlayback = chosenStreams?.itemPlayback,
                                            ),
                                        )
                                        onDismissRequest.invoke()
                                    },
                                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                                )
                        }
                },
                onShowOverview = { actions.onShowOverview.invoke(item) },
                onClearChosenStreams = {
                    actions.onClearChosenStreams.invoke(chosenStreams)
                },
                onClickDelete = { showDeleteDialog = true },
                onClickPlayWith = { showPlayWithDialog = true },
            )
        }
    DialogPopup(
        showDialog = true,
        title = item.title ?: "",
        dialogItems = dialogItems,
        onDismissRequest = onDismissRequest,
        dismissOnClick = false,
        waitToLoad = contextMenu.fromLongClick,
    )
    if (chooseVersion != null) {
        chooseVersion?.let { params ->
            DialogPopup(
                showDialog = true,
                title = params.title,
                dialogItems = params.items,
                onDismissRequest = { chooseVersion = null },
                dismissOnClick = true,
                waitToLoad = params.fromLongClick,
                elevation = 3.dp,
            )
        }
    }
    if (showDeleteDialog) {
        ConfirmDeleteDialog(
            itemTitle = item.title ?: "",
            onCancel = { showDeleteDialog = false },
            onConfirm = {
                actions.onDeleteItem.invoke(item)
                onDismissRequest.invoke()
            },
            elevation = 3.dp,
        )
    }
    if (showPlayWithDialog) {
        val dialogItems =
            remember {
                buildPlayWith(resources) { transcode, backend ->
                    onDismissRequest.invoke()
                    actions.navigateTo(
                        Destination.Playback(
                            itemId = item.id,
                            positionMs = item.resumeMs,
                            forceTranscoding = transcode,
                            backend = backend,
                        ),
                    )
                }
            }
        DialogPopup(
            showDialog = true,
            title = stringResource(R.string.play_with),
            dialogItems = dialogItems,
            onDismissRequest = { showPlayWithDialog = false },
            dismissOnClick = true,
            waitToLoad = false,
            elevation = 3.dp,
        )
    }
}

/** The Compose rows for the shared, presentation-free [ContextMenuEntry] list (logic in ContextMenuModel.kt) */
private fun List<ContextMenuEntry>.toDialogItems(): List<DialogItem> = map { it.toDialogItem() }

private fun ContextMenuEntry.toDialogItem(): DialogItem =
    when (val icon = icon) {
        is MenuEntryIcon.Material -> {
            DialogItem(
                text = text,
                icon =
                    when (icon.icon) {
                        MenuIcon.ARROW_FORWARD -> Icons.Default.ArrowForward
                        MenuIcon.ARROW_FORWARD_MIRRORED -> Icons.AutoMirrored.Filled.ArrowForward
                        MenuIcon.PLAY -> Icons.Default.PlayArrow
                        MenuIcon.REFRESH -> Icons.Default.Refresh
                        MenuIcon.DELETE -> Icons.Default.Delete
                        MenuIcon.INFO -> Icons.Default.Info
                    },
                iconColor = iconColor?.let { Color(it) },
                dismissOnClick = dismissOnClick,
                onClick = onClick,
            )
        }

        is MenuEntryIcon.FontAwesome -> {
            DialogItem(
                headlineContent = { Text(text = text) },
                leadingContent = {
                    Text(
                        text = stringResource(id = icon.glyph),
                        fontFamily = FontAwesome,
                        color = iconColor?.let { Color(it) } ?: Color.Unspecified,
                    )
                },
                onClick = onClick,
                dismissOnClick = dismissOnClick,
            )
        }

        null -> {
            DialogItem(text = text, dismissOnClick = dismissOnClick, onClick = onClick)
        }
    }

private fun buildContextMenuItems(
    resources: Resources,
    contextMenu: ContextMenu.ForBaseItem,
    item: BaseItem,
    seriesId: UUID?,
    sourceId: UUID?,
    watched: Boolean,
    favorite: Boolean,
    canClearChosenStreams: Boolean,
    actions: ContextMenuActions,
    onChooseVersion: () -> Unit,
    onChooseTracks: (MediaStreamType) -> Unit,
    onShowOverview: () -> Unit,
    onClearChosenStreams: () -> Unit,
    onClickDelete: () -> Unit,
    onClickPlayWith: () -> Unit,
): List<DialogItem> =
    buildContextMenuEntries(
        resources = resources,
        contextMenu = contextMenu,
        item = item,
        seriesId = seriesId,
        sourceId = sourceId,
        watched = watched,
        favorite = favorite,
        canClearChosenStreams = canClearChosenStreams,
        actions = actions,
        onChooseVersion = onChooseVersion,
        onChooseTracks = onChooseTracks,
        onShowOverview = onShowOverview,
        onClearChosenStreams = onClearChosenStreams,
        onClickDelete = onClickDelete,
        onClickPlayWith = onClickPlayWith,
    ).toDialogItems()

private fun buildPlayWith(
    resources: Resources,
    onClick: (Boolean, PlayerBackend?) -> Unit,
) = playWithChoices(resources).map { choice ->
    DialogItem(
        choice.title,
        dismissOnClick = true,
    ) {
        onClick.invoke(choice.transcode, choice.backend)
    }
}

@Composable
fun ContextMenu(
    onDismissRequest: () -> Unit,
    item: ContextMenu.ForPerson,
    actions: PersonContextActions,
) {
    val person = item.person
    val resources = LocalResources.current
    val dialogItems = buildPersonContextMenuEntries(resources, person, actions).toDialogItems()
    DialogPopup(
        showDialog = true,
        title = person.name ?: "",
        dialogItems = dialogItems,
        onDismissRequest = onDismissRequest,
        dismissOnClick = true,
        waitToLoad = item.fromLongClick,
    )
}
