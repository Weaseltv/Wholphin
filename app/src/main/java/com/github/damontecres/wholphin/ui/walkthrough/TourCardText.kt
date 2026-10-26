package com.github.damontecres.wholphin.ui.walkthrough

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.nav.TourRailIds
import com.github.damontecres.wholphin.ui.nav.WeaselNavIcons
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.RailAccents
import com.github.damontecres.wholphin.ui.theme.sectionAccent
import org.jellyfin.sdk.model.api.CollectionType

/** Body text of a card: at least 18 sp on the TV (plan 3.3) */
@Composable
fun TourBody(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NeonBoard.Text,
) {
    Text(text = text, style = NeonType.body(19.sp), color = color, modifier = modifier)
}

/** A remote key shown as a chip: OK, BACK, ◀ … */
@Composable
fun KeyChip(label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .defaultMinSize(minWidth = 36.dp, minHeight = 30.dp)
                .background(NeonBoard.Card2)
                .border(1.dp, NeonBoard.Line2)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = NeonType.chip(), color = NeonBoard.Text)
    }
}

/** [keys] as chips, then [text] */
@Composable
fun KeyLine(
    keys: List<String>,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { keys.forEach { KeyChip(it) } }
        TourBody(text)
    }
}

/** Joins labels the way the copy reads: "A", "A and B", "A, B and C" */
@Composable
fun joinLabels(labels: List<String>): String =
    when (labels.size) {
        0 -> ""
        1 -> labels[0]
        else -> stringResource(R.string.tour_and, labels.dropLast(1).joinToString(", "), labels.last())
    }

@Composable
fun RemoteCardText(ctx: TourContext) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyLine(listOf("▲", "▼", "◀", "▶"), stringResource(R.string.tour_remote_arrows))
        KeyLine(listOf("OK"), stringResource(R.string.tour_remote_ok))
        TourBody(stringResource(R.string.tour_remote_hold_ok))
        KeyLine(listOf("BACK"), stringResource(R.string.tour_remote_back))
        KeyLine(listOf("⏯"), stringResource(R.string.tour_remote_play))
    }
}

/** Card 2: instructions, then the focused menu row's description, then the done line */
@Composable
fun MenuTryCardText(
    ctx: TourContext,
    state: TourCardState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val focused = state.railFocus
        when {
            focused != null -> {
                TourBody(railDescription(ctx, focused), color = NeonBoard.Volt)
                TourBody(stringResource(R.string.tour_menu_try_back), color = NeonBoard.Mid)
            }

            state.menuStepDone -> {
                TourBody(stringResource(R.string.tour_menu_try_done), color = NeonBoard.Volt)
            }

            else -> {
                TourBody(stringResource(R.string.tour_menu_try_body))
                TourBody(stringResource(R.string.tour_menu_try_note), color = NeonBoard.Mid)
            }
        }
    }
}

/** One line about the menu row [id], with the label the menu shows */
@Composable
fun railDescription(
    ctx: TourContext,
    id: String,
): String {
    val label = ctx.labelOf(id) ?: ""
    return when (id) {
        TourRailIds.PROFILE -> stringResource(R.string.tour_rail_profile)
        TourRailIds.NOW_PLAYING -> stringResource(R.string.tour_rail_now_playing)
        TourRailIds.SEARCH -> stringResource(R.string.tour_rail_search)
        TourRailIds.HOME -> stringResource(R.string.tour_rail_home)
        TourRailIds.SETTINGS -> stringResource(R.string.tour_rail_settings)
        NavDrawerItem.Favorites.id -> stringResource(R.string.tour_rail_watchlist, label)
        NavDrawerItem.Discover.id -> stringResource(R.string.tour_rail_requests, label)
        NavDrawerItem.More.id -> stringResource(R.string.tour_rail_more, label)
        else -> stringResource(R.string.tour_rail_library, label)
    }
}

/** Card 3: the real menu, grouped, in its order and with its labels and icons */
@Composable
fun WhereEverythingIsText(ctx: TourContext) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupLine(icons = listOf(RailIcon.Fixed(null, RailAccents.User)), text = stringResource(R.string.tour_where_top))
        val then =
            ctx.rail.filter { it.id == TourRailIds.NOW_PLAYING || it.id == TourRailIds.SEARCH || it.id == TourRailIds.HOME }
        GroupLine(
            icons =
                listOf(
                    RailIcon.Fixed(WeaselNavIcons.SEARCH, RailAccents.Search),
                    RailIcon.Fixed(WeaselNavIcons.HOME, RailAccents.Home),
                ),
            text = stringResource(R.string.tour_where_then, joinLabels(then.map { it.label })),
        )
        val lists = listOfNotNull(ctx.watchlistLabel, ctx.requestsLabel)
        if (lists.isNotEmpty()) {
            GroupLine(
                icons =
                    listOfNotNull(
                        NavDrawerItem.Favorites.takeIf { ctx.watchlistLabel != null },
                        NavDrawerItem.Discover.takeIf { ctx.requestsLabel != null },
                    ).map { RailIcon.Item(it) },
                text = stringResource(R.string.tour_where_lists, joinLabels(lists)),
            )
        }
        if (ctx.libraries.isNotEmpty()) {
            val names = ctx.libraries.map { it.name }
            val text =
                if (names.size > 3) {
                    stringResource(R.string.tour_where_libraries_more, names.take(3).joinToString(", "), names.size - 3)
                } else {
                    joinLabels(names)
                }
            GroupLine(
                icons = ctx.libraries.take(3).map { RailIcon.Item(it) },
                text = stringResource(R.string.tour_where_libraries, text),
            )
        }
        ctx.moreLabel?.let { GroupLine(icons = emptyList(), text = stringResource(R.string.tour_where_under_more, it)) }
        if (ctx.hasSettings) {
            GroupLine(
                icons = listOf(RailIcon.Fixed(WeaselNavIcons.SETTINGS, RailAccents.Settings)),
                text = stringResource(R.string.tour_where_bottom),
            )
        }
    }
}

sealed interface RailIcon {
    /** A fixed rail glyph by drawable name; null name = the profile picture's place, drawn as a dot */
    data class Fixed(
        val name: String?,
        val accent: Color,
    ) : RailIcon

    data class Item(
        val item: NavDrawerItem,
    ) : RailIcon
}

@Composable
private fun GroupLine(
    icons: List<RailIcon>,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.defaultMinSize(minWidth = 80.dp),
        ) {
            icons.forEach { RailGlyph(it) }
        }
        TourBody(text, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
fun RailGlyph(
    icon: RailIcon,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val (res, accent) =
        when (icon) {
            is RailIcon.Fixed -> (icon.name?.let { WeaselNavIcons.fixed(context, it) }) to icon.accent
            is RailIcon.Item -> WeaselNavIcons.navIconFor(context, icon.item) to sectionAccent(icon.item)
        }
    if (res != null) {
        Icon(painter = painterResource(res), contentDescription = null, tint = accent, modifier = modifier.size(24.dp))
    } else {
        // No WeaselPlex glyph (the profile picture, or a library without one): a dot in the row's colour
        Box(modifier = modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).background(accent.copy(alpha = if (icon is RailIcon.Item && icon.item is ServerNavDrawerItem) .8f else 1f)))
        }
    }
}

/** Plain lines of body text, [spacing] apart */
@Composable
fun TourLines(
    vararg lines: String?,
    spacing: Int = 12,
) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        lines.filterNotNull().forEach { TourBody(it) }
    }
}

@Composable
fun HomeCardText(ctx: TourContext) {
    TourLines(
        stringResource(R.string.tour_home_top),
        stringResource(if (ctx.isKid) R.string.tour_home_rows_kid else R.string.tour_home_rows),
        stringResource(R.string.tour_home_view_more),
        stringResource(R.string.tour_home_hold),
    )
}

@Composable
fun TitleCardText(ctx: TourContext) {
    if (ctx.isKid) {
        TourLines(
            stringResource(R.string.tour_title_play_kid),
            stringResource(R.string.tour_title_watchlist_kid),
            stringResource(R.string.tour_title_shows),
        )
    } else {
        TourLines(
            stringResource(R.string.tour_title_play),
            stringResource(R.string.tour_title_watchlist),
            stringResource(R.string.tour_title_more),
            stringResource(R.string.tour_title_shows),
        )
    }
}

@Composable
fun WatchingCardText(ctx: TourContext) {
    if (ctx.externalPlayer) {
        TourLines(stringResource(R.string.tour_watch_external))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TourBody(stringResource(if (ctx.oneClickPause) R.string.tour_watch_show_one_click else R.string.tour_watch_show))
        KeyLine(
            listOf("◀", "▶"),
            stringResource(R.string.tour_watch_skip, ctx.skipBackSeconds.toInt(), ctx.skipForwardSeconds.toInt()),
        )
        TourBody(stringResource(R.string.tour_watch_tracks))
        TourBody(stringResource(R.string.tour_watch_back))
    }
}

@Composable
fun SearchCardText(ctx: TourContext) {
    TourLines(
        stringResource(R.string.tour_search_body),
        ctx.requestsLabel?.let { stringResource(R.string.tour_search_requests, it, stringResource(R.string.requests_history)) },
    )
}

@Composable
fun SettingsCardText() {
    TourLines(
        stringResource(R.string.tour_settings_body),
        stringResource(R.string.tour_settings_help),
    )
}

/** One page (up to [MENU_PAGE_SIZE] rows) of the full menu, in its order, with its labels and icons */
@Composable
fun MenuInventoryText(
    ctx: TourContext,
    page: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ctx.rail.drop(page * MENU_PAGE_SIZE).take(MENU_PAGE_SIZE).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RailGlyph(railIcon(row))
                Column {
                    TourBody(railDescription(ctx, row.id))
                    if (row.item == NavDrawerItem.More && ctx.moreRows.isNotEmpty()) {
                        TourBody(
                            stringResource(R.string.tour_menu_more_items, row.label, joinLabels(ctx.moreRows.map { it.label })),
                            color = NeonBoard.Mid,
                        )
                    }
                }
            }
        }
    }
}

const val MENU_PAGE_SIZE = 5

fun railIcon(row: TourRailRow): RailIcon =
    when (row.id) {
        TourRailIds.PROFILE -> RailIcon.Fixed(null, RailAccents.User)
        TourRailIds.NOW_PLAYING -> RailIcon.Fixed(null, RailAccents.NowPlaying)
        TourRailIds.SEARCH -> RailIcon.Fixed(WeaselNavIcons.SEARCH, RailAccents.Search)
        TourRailIds.HOME -> RailIcon.Fixed(WeaselNavIcons.HOME, RailAccents.Home)
        TourRailIds.SETTINGS -> RailIcon.Fixed(WeaselNavIcons.SETTINGS, RailAccents.Settings)
        else -> row.item?.let { RailIcon.Item(it) } ?: RailIcon.Fixed(null, NeonBoard.Volt)
    }

@Composable
fun CollectionsCardText(ctx: TourContext) {
    val collections = ctx.allLibraries.firstOrNull { it.type == CollectionType.BOXSETS }
    val playlists = ctx.allLibraries.firstOrNull { it.type == CollectionType.PLAYLISTS }
    TourLines(
        collections?.let { stringResource(R.string.tour_collections_body, it.name) },
        playlists?.let { stringResource(R.string.tour_playlists_body, it.name) }
            ?: stringResource(R.string.tour_playlists_none),
    )
}

@Composable
fun LiveTvCardText(ctx: TourContext) {
    val live = ctx.allLibraries.firstOrNull { it.type == CollectionType.LIVETV } ?: return
    TourLines(stringResource(R.string.tour_live_body, live.name))
}
