package com.github.damontecres.wholphin.leanback.widget.dialog

import android.app.Dialog
import android.content.Context
import android.view.View
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.ui.components.ChosenTrackResult
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.buildContextMenuEntries
import com.github.damontecres.wholphin.ui.components.buildPersonContextMenuEntries
import com.github.damontecres.wholphin.ui.components.destination
import com.github.damontecres.wholphin.ui.components.playWithChoices
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * The context menu as Views (`ContextMenuDialog` / ContextMenu.kt): the entries come from the shared plain-Kotlin
 * builder ([buildContextMenuEntries], same entries per item type and state as before), shown in a [MenuDialog] titled
 * with the item's (or person's) name. Follow-up dialogs open on top of the menu, as before: choose version / audio /
 * subtitles (elevation 3), the delete confirmation, and "Play with". Closing a follow-up returns to the menu; a choice
 * in one closes everything.
 *
 * Any screen opens it on long press: `ContextMenuDialog.show(context, menu, …)`. For the common grid / row case use
 * [ContextMenuHelper].
 */
object ContextMenuDialog {
    /**
     * Show [contextMenu]. [onDismissRequest] runs once the menu closes because of BACK or a choice.
     *
     * @param getMediaSource resolves the source whose tracks the audio / subtitle choosers list; null disables them
     * @return the menu dialog
     */
    fun show(
        context: Context,
        contextMenu: ContextMenu,
        getMediaSource: ((dto: BaseItemDto, itemPlayback: ItemPlayback?) -> MediaSourceInfo?)?,
        preferredSubtitleLanguage: String?,
        onDismissRequest: () -> Unit,
        accent: Int? = null,
        opener: View? = null,
    ): Dialog =
        when (contextMenu) {
            is ContextMenu.ForBaseItem -> {
                showForItem(context, contextMenu, getMediaSource, preferredSubtitleLanguage, onDismissRequest, accent, opener)
            }

            is ContextMenu.ForPerson -> {
                MenuDialog.show(
                    context = context,
                    title = contextMenu.person.name ?: "",
                    entries =
                        buildPersonContextMenuEntries(context.resources, contextMenu.person, contextMenu.actions)
                            .map { it.toMenuItem() },
                    onDismissRequest = onDismissRequest,
                    dismissOnClick = true,
                    waitToLoad = contextMenu.fromLongClick,
                    accent = accent,
                    opener = opener,
                )
            }
        }

    private fun showForItem(
        context: Context,
        contextMenu: ContextMenu.ForBaseItem,
        getMediaSource: ((dto: BaseItemDto, itemPlayback: ItemPlayback?) -> MediaSourceInfo?)?,
        preferredSubtitleLanguage: String?,
        onDismissRequest: () -> Unit,
        accent: Int?,
        opener: View?,
    ): Dialog {
        val resources = context.resources
        val item = contextMenu.item
        val chosenStreams = contextMenu.chosenStreams
        val actions = contextMenu.actions
        lateinit var menu: MenuDialog
        var followUp: Dialog? = null
        val menuAccent: () -> Int = { menu.accent }

        // A choice in a follow-up closes the follow-up and the menu, as `onDismissRequest()` did in Compose
        fun closeAll() {
            followUp?.dismiss()
            followUp = null
            menu.requestDismiss()
        }

        fun showFollowUp(dialog: Dialog) {
            followUp = dialog
            dialog.setOnDismissListener { if (followUp === dialog) followUp = null }
            dialog.show()
        }

        val entries =
            buildContextMenuEntries(
                resources = resources,
                contextMenu = contextMenu,
                item = item,
                seriesId = item.data.seriesId,
                sourceId = chosenStreams?.source?.id?.toUUIDOrNull(),
                watched = item.played,
                favorite = item.favorite,
                canClearChosenStreams = chosenStreams.let { it?.itemPlayback != null || it?.plc != null },
                actions = actions,
                onChooseVersion = {
                    val params =
                        chooseVersionParams(
                            resources,
                            item.data.mediaSources.orEmpty(),
                            chosenStreams?.source?.id?.toUUIDOrNull(),
                        ) { idx ->
                            val source = item.data.mediaSources!![idx]
                            actions.onChooseVersion.invoke(item, source)
                            closeAll()
                        }
                    showFollowUp(MenuDialog(context, params, dismissOnClick = true, elevationDp = 3f, accent = menuAccent()))
                },
                onChooseTracks = { type ->
                    getMediaSource?.invoke(item.data, chosenStreams?.itemPlayback)?.let { source ->
                        val params =
                            chooseStreamParams(
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
                                    closeAll()
                                },
                                preferredSubtitleLanguage = preferredSubtitleLanguage,
                            )
                        showFollowUp(MenuDialog(context, params, dismissOnClick = true, elevationDp = 3f, accent = menuAccent()))
                    }
                },
                onShowOverview = { actions.onShowOverview.invoke(item) },
                onClearChosenStreams = { actions.onClearChosenStreams.invoke(chosenStreams) },
                onClickDelete = {
                    var confirm: ConfirmDeleteDialog? = null
                    confirm =
                        ConfirmDeleteDialog(
                            context = context,
                            itemTitle = item.title ?: "",
                            onCancel = { confirm?.dismiss() },
                            onConfirm = {
                                actions.onDeleteItem.invoke(item)
                                closeAll()
                            },
                            elevationDp = 3f,
                            accent = menuAccent(),
                        )
                    showFollowUp(confirm)
                },
                onClickPlayWith = {
                    val choices =
                        playWithChoices(resources).map { choice ->
                            MenuItem.text(choice.title, dismissOnClick = true) {
                                closeAll()
                                actions.navigateTo(choice.destination(item))
                            }
                        }
                    showFollowUp(
                        MenuDialog(
                            context,
                            resources.getString(R.string.play_with),
                            choices,
                            dismissOnClick = true,
                            waitToLoad = false,
                            elevationDp = 3f,
                            accent = menuAccent(),
                        ),
                    )
                },
            )
        menu =
            MenuDialog(
                context = context,
                title = item.title ?: "",
                entries = entries.map { it.toMenuItem() },
                dismissOnClick = false,
                waitToLoad = contextMenu.fromLongClick,
                accent = accent,
                opener = opener,
            )
        menu.onDismissRequest = {
            followUp?.dismiss()
            followUp = null
            onDismissRequest()
        }
        menu.show()
        return menu
    }
}
