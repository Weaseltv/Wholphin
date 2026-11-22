package com.github.damontecres.wholphin.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.supportedPlayableTypes
import com.github.damontecres.wholphin.util.supportedShufflableTypes
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import kotlin.time.Duration

/*
 * The context menu's model and entry logic without Compose (no-Compose rewrite): moved here from ContextMenu.kt so
 * the Compose menu and the View menu (`leanback.widget.dialog.ContextMenuDialog`) build the same entries.
 */

sealed interface ContextMenu {
    data class ForBaseItem(
        val fromLongClick: Boolean,
        val item: BaseItem,
        val index: Int = UNSPECIFIED_INDEX,
        val chosenStreams: ChosenStreams?,
        val showGoTo: Boolean,
        val showStreamChoices: Boolean,
        val canDelete: Boolean,
        val canRemoveContinueWatching: Boolean,
        val canRemoveNextUp: Boolean,
        val actions: ContextMenuActions,
        val showRemoveFromPlaylist: Boolean = false,
        /**
         * False for Home's streaming service tiles, which are fetched without watched and favourite status
         * (see [com.github.damontecres.wholphin.services.StreamingCollections.fetch]), so the entries couldn't show
         * their state; "Mark watched" there would also mark every title on the service (owner, 2026-11-12)
         */
        val showWatchedAndFavorite: Boolean = true,
    ) : ContextMenu

    data class ForPerson(
        val fromLongClick: Boolean,
        val person: Person,
        val actions: PersonContextActions,
    ) : ContextMenu

    companion object {
        const val UNSPECIFIED_INDEX = -12
    }
}

data class ContextMenuActions(
    val navigateTo: (Destination) -> Unit,
    val onShowOverview: (BaseItem) -> Unit,
    val onClickWatch: (UUID, Boolean) -> Unit,
    val onClickFavorite: (UUID, Boolean) -> Unit,
    val onClickAddPlaylist: (UUID) -> Unit,
    val onSendMediaInfo: (UUID) -> Unit,
    val onDeleteItem: (BaseItem) -> Unit,
    val onChooseVersion: (BaseItem, MediaSourceInfo) -> Unit,
    val onChooseTracks: (ChosenTrackResult) -> Unit,
    val onClearChosenStreams: (ChosenStreams?) -> Unit,
    val onClickGoTo: (BaseItem) -> Unit = { navigateTo(it.destination()) },
    val onClickRemoveFromNextUp: (BaseItem) -> Unit = {},
    val onRemoveFromPlaylist: (Int, UUID) -> Unit = { _, _ -> },
    /** Set where the item's row can be reordered, e.g. Home's Streaming Services row. */
    val onReorder: ((BaseItem) -> Unit)? = null,
    /** With [onReorder], puts the row back in its default order. */
    val onResetOrder: (() -> Unit)? = null,
)

data class PersonContextActions(
    val navigateTo: (Destination) -> Unit,
    val onClickFavorite: (UUID, Boolean) -> Unit,
)

data class ChosenTrackResult(
    val item: BaseItem,
    val streamType: MediaStreamType,
    val trackIndex: Int,
    val itemPlayback: ItemPlayback?,
)

/** The Material icons the menu entries showed (`Icons.Default.*`) */
enum class MenuIcon {
    ARROW_FORWARD,

    /** `Icons.AutoMirrored.Filled.ArrowForward` */
    ARROW_FORWARD_MIRRORED,
    PLAY,
    REFRESH,
    DELETE,
    INFO,
}

/** The leading icon of a menu entry: a Material icon or a Font Awesome glyph string */
sealed interface MenuEntryIcon {
    data class Material(
        val icon: MenuIcon,
    ) : MenuEntryIcon

    data class FontAwesome(
        @param:StringRes val glyph: Int,
    ) : MenuEntryIcon
}

/** Red at 80 %, the delete entry's icon (`Color.Red.copy(alpha = .8f)`) */
const val MENU_DELETE_ICON_COLOR = 0xCCFF0000.toInt()

/** Red, the "Remove favorite" heart (`Color.Red`) */
const val MENU_FAVORITE_ICON_COLOR = 0xFFFF0000.toInt()

/** One context menu entry, presentation-free */
data class ContextMenuEntry(
    val text: String,
    val icon: MenuEntryIcon?,
    /** ARGB, null for the content colour */
    val iconColor: Int? = null,
    val dismissOnClick: Boolean,
    val onClick: () -> Unit,
)

private fun entry(
    text: String,
    icon: MenuIcon,
    iconColor: Int? = null,
    dismissOnClick: Boolean,
    onClick: () -> Unit,
) = ContextMenuEntry(text, MenuEntryIcon.Material(icon), iconColor, dismissOnClick, onClick)

private fun faEntry(
    text: String,
    @StringRes glyph: Int,
    iconColor: Int? = null,
    dismissOnClick: Boolean,
    onClick: () -> Unit,
) = ContextMenuEntry(text, MenuEntryIcon.FontAwesome(glyph), iconColor, dismissOnClick, onClick)

/**
 * The entries of an item's context menu, in order, for its type and state. [onChooseVersion], [onChooseTracks],
 * [onClickDelete] and [onClickPlayWith] open the follow-up dialogs; the rest call [actions].
 */
fun buildContextMenuEntries(
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
): List<ContextMenuEntry> =
    buildList {
        if (contextMenu.showGoTo) {
            add(entry(resources.getString(R.string.go_to), MenuIcon.ARROW_FORWARD, dismissOnClick = true) { actions.onClickGoTo(item) })
        }
        actions.onReorder?.let { onReorder ->
            add(
                faEntry(
                    resources.getString(R.string.reorder),
                    R.string.fa_arrow_left_arrow_right,
                    dismissOnClick = true,
                ) { onReorder(item) },
            )
        }
        if (item.type in supportedPlayableTypes) {
            if (item.playbackPosition > Duration.ZERO) {
                add(
                    entry(resources.getString(R.string.resume), MenuIcon.PLAY, dismissOnClick = true) {
                        actions.navigateTo(Destination.Playback(item.id, item.playbackPosition.inWholeMilliseconds))
                    },
                )
                add(
                    entry(resources.getString(R.string.restart), MenuIcon.REFRESH, dismissOnClick = true) {
                        actions.navigateTo(Destination.Playback(item.id, 0L))
                    },
                )
            } else {
                add(
                    entry(resources.getString(R.string.play), MenuIcon.PLAY, dismissOnClick = true) {
                        actions.navigateTo(Destination.Playback(item.id, 0L))
                    },
                )
            }
        }
        if (item.type in supportedShufflableTypes) {
            add(
                faEntry(resources.getString(R.string.shuffle), R.string.fa_shuffle, dismissOnClick = true) {
                    actions.navigateTo(Destination.PlaybackList(itemId = item.id, shuffle = true))
                },
            )
        }
        if (contextMenu.showStreamChoices) {
            item.data.mediaSources?.letNotEmpty { sources ->
                val source =
                    sourceId?.let { sources.firstOrNull { it.id?.toUUIDOrNull() == sourceId } }
                        ?: sources.firstOrNull()
                source?.mediaStreams?.letNotEmpty { streams ->
                    val audioCount = streams.count { it.type == MediaStreamType.AUDIO }
                    val subtitleCount = streams.count { it.type == MediaStreamType.SUBTITLE }
                    if (audioCount > 1) {
                        add(
                            faEntry(
                                resources.getString(R.string.choose_stream, resources.getString(R.string.audio)),
                                R.string.fa_volume_low,
                                dismissOnClick = false,
                            ) { onChooseTracks.invoke(MediaStreamType.AUDIO) },
                        )
                    }
                    if (subtitleCount > 0) {
                        add(
                            faEntry(
                                resources.getString(R.string.choose_stream, resources.getString(R.string.subtitles)),
                                R.string.fa_closed_captioning,
                                dismissOnClick = false,
                            ) { onChooseTracks.invoke(MediaStreamType.SUBTITLE) },
                        )
                    }
                }
                if (sources.size > 1) {
                    add(
                        faEntry(
                            resources.getString(R.string.choose_stream, resources.getString(R.string.version)),
                            R.string.fa_file_video,
                            dismissOnClick = false,
                        ) { onChooseVersion.invoke() },
                    )
                }
            }
        }
        if (contextMenu.showRemoveFromPlaylist) {
            add(
                faEntry(resources.getString(R.string.remove_from_playlist), R.string.fa_circle_xmark, dismissOnClick = true) {
                    actions.onRemoveFromPlaylist.invoke(contextMenu.index, item.id)
                },
            )
        }
        add(
            faEntry(resources.getString(R.string.add_to_playlist), R.string.fa_list_ul, dismissOnClick = true) {
                actions.onClickAddPlaylist.invoke(item.id)
            },
        )
        if (contextMenu.canDelete) {
            add(
                entry(resources.getString(R.string.delete), MenuIcon.DELETE, MENU_DELETE_ICON_COLOR, dismissOnClick = false) {
                    onClickDelete.invoke()
                },
            )
        }
        if (contextMenu.canRemoveContinueWatching && !watched && item.playbackPosition > Duration.ZERO) {
            add(
                faEntry(resources.getString(R.string.remove_continue_watching), R.string.fa_eye, dismissOnClick = true) {
                    actions.onClickWatch.invoke(item.id, false)
                },
            )
        }
        if (contextMenu.canRemoveNextUp && item.type == BaseItemKind.EPISODE && item.data.seriesId != null) {
            add(
                faEntry(resources.getString(R.string.remove_next_up), R.string.fa_tag, dismissOnClick = true) {
                    actions.onClickRemoveFromNextUp.invoke(item)
                },
            )
        }
        if (contextMenu.showWatchedAndFavorite) {
            add(
                faEntry(
                    resources.getString(if (watched) R.string.mark_unwatched else R.string.mark_watched),
                    if (watched) R.string.fa_eye else R.string.fa_eye_slash,
                    dismissOnClick = true,
                ) { actions.onClickWatch.invoke(item.id, !watched) },
            )
            add(
                faEntry(
                    resources.getString(if (favorite) R.string.remove_favorite else R.string.add_favorite),
                    R.string.fa_heart,
                    iconColor = if (favorite) MENU_FAVORITE_ICON_COLOR else null,
                    dismissOnClick = true,
                ) { actions.onClickFavorite.invoke(item.id, !favorite) },
            )
        }
        seriesId?.let {
            add(
                entry(resources.getString(R.string.go_to_series), MenuIcon.ARROW_FORWARD_MIRRORED, dismissOnClick = true) {
                    actions.navigateTo(Destination.MediaItem(seriesId, BaseItemKind.SERIES, null))
                },
            )
        }
        if (item.data.mediaSources?.isNotEmpty() == true) {
            add(entry(resources.getString(R.string.media_information), MenuIcon.INFO, dismissOnClick = true) { onShowOverview.invoke() })
        }
        if (contextMenu.showStreamChoices && canClearChosenStreams) {
            add(entry(resources.getString(R.string.clear_track_choices), MenuIcon.DELETE, dismissOnClick = true) { onClearChosenStreams() })
        }
        if (item.type in supportedPlayableTypes) {
            add(entry(resources.getString(R.string.play_with), MenuIcon.PLAY, dismissOnClick = false, onClick = onClickPlayWith))
        }
        if (item.data.mediaSources?.isNotEmpty() == true) {
            add(
                faEntry(resources.getString(R.string.send_media_info_log_to_server), R.string.fa_file_video, dismissOnClick = true) {
                    actions.onSendMediaInfo.invoke(item.id)
                },
            )
        }
        actions.onResetOrder?.let { onResetOrder ->
            add(faEntry(resources.getString(R.string.reset_order), R.string.fa_arrows_rotate, dismissOnClick = true) { onResetOrder() })
        }
    }

/** One "Play with" choice: a player backend, or transcoding */
data class PlayWithChoice(
    val title: String,
    val transcode: Boolean,
    val backend: PlayerBackend?,
)

/** The "Play with" dialog's choices: every player backend except "prefer MPV", then Transcoding */
fun playWithChoices(resources: Resources): List<PlayWithChoice> =
    buildList {
        PlayerBackend.entries
            .filterNot { it == PlayerBackend.UNRECOGNIZED }
            .zip(resources.getStringArray(R.array.player_backend_options))
            .filterNot { it.first == PlayerBackend.PREFER_MPV }
            .forEach { (backend, title) -> add(PlayWithChoice(title, false, backend)) }
        add(PlayWithChoice(resources.getString(R.string.transcoding), true, null))
    }

/** The destination a "Play with" choice opens for [item] */
fun PlayWithChoice.destination(item: BaseItem): Destination =
    Destination.Playback(
        itemId = item.id,
        positionMs = item.resumeMs,
        forceTranscoding = transcode,
        backend = backend,
    )

/** A person's context menu: Go to, then add / remove favorite */
fun buildPersonContextMenuEntries(
    resources: Resources,
    person: Person,
    actions: PersonContextActions,
): List<ContextMenuEntry> {
    val itemId = person.id
    return listOf(
        entry(resources.getString(R.string.go_to), MenuIcon.ARROW_FORWARD, dismissOnClick = false) {
            actions.navigateTo(Destination.MediaItem(itemId, BaseItemKind.PERSON, null))
        },
        faEntry(
            resources.getString(if (person.favorite) R.string.remove_favorite else R.string.add_favorite),
            R.string.fa_heart,
            iconColor = if (person.favorite) MENU_FAVORITE_ICON_COLOR else null,
            dismissOnClick = false,
        ) { actions.onClickFavorite.invoke(itemId, !person.favorite) },
    )
}
