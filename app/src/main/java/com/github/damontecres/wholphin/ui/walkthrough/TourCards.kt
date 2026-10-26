package com.github.damontecres.wholphin.ui.walkthrough

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavDrawerItemState
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.nav.TourRailIds
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.CollectionType
import kotlin.time.Duration.Companion.milliseconds

/** The parts of the WeaselPlex tour; [PICKER] is Settings → Help's list of them */
@Serializable
enum class TourChapter {
    PICKER,
    REMOTE,
    MENU,
    HOME,
    TITLE,
    WATCHING,
    SEARCH,
    COLLECTIONS,
    LIVE_TV,
    SETTINGS,
}

enum class TourDepth { ESSENTIAL, DETAIL }

/** One row of the real menu, in the menu's order, with the label it shows */
data class TourRailRow(
    val id: String,
    val label: String,
    val item: NavDrawerItem? = null,
)

/**
 * What the tour reads from the app when it's shown (plan 4.5), so cards use real labels and drop what this person
 * doesn't have.
 */
data class TourContext(
    val rail: List<TourRailRow>,
    val moreRows: List<TourRailRow>,
    val isKid: Boolean,
    val watchlistLabel: String?,
    val requestsLabel: String?,
    val moreLabel: String?,
    val libraries: List<ServerNavDrawerItem>,
    val skipBackSeconds: Long,
    val skipForwardSeconds: Long,
    val oneClickPause: Boolean,
    val externalPlayer: Boolean,
) {
    val hasRequests get() = requestsLabel != null
    val hasSettings get() = !isKid
    val allLibraries get() = libraries + moreRows.mapNotNull { it.item as? ServerNavDrawerItem }
    val hasLiveTv get() = allLibraries.any { it.type == CollectionType.LIVETV }
    val hasCollections get() = allLibraries.any { it.type == CollectionType.BOXSETS || it.type == CollectionType.PLAYLISTS }

    /** The label of the menu row with [id], or null if this person's menu doesn't have it */
    fun labelOf(id: String): String? = (rail + moreRows).firstOrNull { it.id == id }?.label

    companion object {
        fun build(
            context: Context,
            nav: NavDrawerItemState,
            isKid: Boolean,
            preferences: UserPreferences,
        ): TourContext {
            fun row(item: NavDrawerItem) = TourRailRow(item.id, item.name(context), item)
            val all = nav.items + nav.moreItems
            val rail =
                buildList {
                    add(TourRailRow(TourRailIds.PROFILE, context.getString(R.string.tour_you)))
                    if (nav.nowPlayingEnabled) add(TourRailRow(TourRailIds.NOW_PLAYING, context.getString(R.string.now_playing)))
                    add(TourRailRow(TourRailIds.SEARCH, context.getString(R.string.search)))
                    add(TourRailRow(TourRailIds.HOME, context.getString(R.string.home)))
                    nav.items.forEach { add(row(it)) }
                    if (nav.moreItems.isNotEmpty()) add(row(NavDrawerItem.More))
                    if (!isKid) add(TourRailRow(TourRailIds.SETTINGS, context.getString(R.string.settings)))
                }
            val playback = preferences.appPreferences.playbackPreferences
            return TourContext(
                rail = rail,
                moreRows = nav.moreItems.map { row(it) },
                isKid = isKid,
                watchlistLabel = all.firstOrNull { it is NavDrawerItem.Favorites }?.name(context),
                requestsLabel = all.firstOrNull { it is NavDrawerItem.Discover }?.name(context),
                moreLabel = if (nav.moreItems.isNotEmpty()) NavDrawerItem.More.name(context) else null,
                libraries = nav.items.filterIsInstance<ServerNavDrawerItem>(),
                skipBackSeconds = playback.skipBackMs.milliseconds.inWholeSeconds,
                skipForwardSeconds = playback.skipForwardMs.milliseconds.inWholeSeconds,
                oneClickPause = playback.oneClickPause,
                externalPlayer = playback.playerBackend == PlayerBackend.EXTERNAL_PLAYER,
            )
        }
    }
}

/**
 * One card of the tour. The page keeps the current card's [id] and resolves the visible list every time, so a card
 * appearing or going away never moves the person to another card.
 */
class TourCard(
    val id: String,
    val chapter: TourChapter,
    val depth: TourDepth,
    val title: @Composable (TourContext) -> String,
    val isAvailable: (TourContext) -> Boolean = { true },
    /** The live menu step: text only, and Previous is hidden so Next is the leftmost button */
    val liveMenuStep: Boolean = false,
    val illustration: (@Composable (TourContext) -> Unit)? = null,
    val content: @Composable (TourContext, TourCardState) -> Unit,
)

private fun res(
    @StringRes id: Int,
): @Composable (TourContext) -> String = { stringResource(id) }

/** State the page hands to a card: only the live menu step uses it */
data class TourCardState(
    val railFocus: String? = null,
    val menuStepDone: Boolean = false,
)

@StringRes
fun TourChapter.label(context: TourContext?): Int =
    when (this) {
        TourChapter.PICKER -> R.string.tour_chapter_picker
        TourChapter.REMOTE -> R.string.tour_chapter_remote
        TourChapter.MENU -> R.string.tour_chapter_menu
        TourChapter.HOME -> R.string.tour_chapter_home
        TourChapter.TITLE -> R.string.tour_chapter_title
        TourChapter.WATCHING -> R.string.tour_chapter_watching
        TourChapter.SEARCH -> if (context?.hasRequests == true) R.string.tour_chapter_search_requests else R.string.tour_chapter_search
        TourChapter.COLLECTIONS -> R.string.tour_chapter_collections
        TourChapter.LIVE_TV -> R.string.tour_chapter_live_tv
        TourChapter.SETTINGS -> R.string.tour_chapter_settings
    }

object TourCards {
    const val REMOTE = "remote"
    const val MENU_LIVE = "menu_live"
    const val MENU_WHERE = "menu_where"
    const val HOME = "home"
    const val TITLE = "title"
    const val WATCHING = "watching"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /** Enough pages of [MENU_PAGE_SIZE] rows for any menu */
    private const val MENU_PAGES = 6

    /** Every card, in tour order. Essentials make up the sign-in tour; a chapter shows its essentials, then details. */
    val all: List<TourCard> =
        buildList {
            add(
                TourCard(
                    id = REMOTE,
                    chapter = TourChapter.REMOTE,
                    depth = TourDepth.ESSENTIAL,
                    title = res(R.string.tour_remote_title),
                    illustration = { RemoteIllustration() },
                    content = { ctx, _ -> RemoteCardText(ctx) },
                ),
            )
            add(
                TourCard(
                    id = MENU_LIVE,
                    chapter = TourChapter.MENU,
                    depth = TourDepth.ESSENTIAL,
                    title = res(R.string.tour_menu_try_title),
                    liveMenuStep = true,
                    content = { ctx, state -> MenuTryCardText(ctx, state) },
                ),
            )
            add(
                TourCard(
                    id = MENU_WHERE,
                    chapter = TourChapter.MENU,
                    depth = TourDepth.ESSENTIAL,
                    title = res(R.string.tour_where_title),
                    content = { ctx, _ -> WhereEverythingIsText(ctx) },
                ),
            )
            // Menu chapter: the whole menu, a page of rows at a time
            repeat(MENU_PAGES) { page ->
                add(
                    TourCard(
                        id = "menu_list_$page",
                        chapter = TourChapter.MENU,
                        depth = TourDepth.DETAIL,
                        title = { ctx ->
                            val pages = (ctx.rail.size + MENU_PAGE_SIZE - 1) / MENU_PAGE_SIZE
                            if (pages > 1) {
                                stringResource(R.string.tour_menu_list_title_more, page + 1, pages)
                            } else {
                                stringResource(R.string.tour_menu_list_title)
                            }
                        },
                        isAvailable = { ctx -> ctx.rail.size > page * MENU_PAGE_SIZE },
                        content = { ctx, _ -> MenuInventoryText(ctx, page) },
                    ),
                )
            }
            add(
                TourCard(
                    id = "menu_change",
                    chapter = TourChapter.MENU,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_menu_change_title),
                    isAvailable = { it.hasSettings },
                    content = { _, _ ->
                        TourLines(
                            stringResource(R.string.tour_menu_change),
                            stringResource(R.string.tour_settings_people),
                        )
                    },
                ),
            )
            add(
                TourCard(
                    id = HOME,
                    chapter = TourChapter.HOME,
                    depth = TourDepth.ESSENTIAL,
                    title = res(R.string.tour_home_title),
                    illustration = { HomeIllustration(it) },
                    content = { ctx, _ -> HomeCardText(ctx) },
                ),
            )
            add(
                TourCard(
                    id = "home_streaming",
                    chapter = TourChapter.HOME,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_home_streaming_title),
                    isAvailable = { !it.isKid },
                    content = { _, _ -> TourLines(stringResource(R.string.tour_home_streaming)) },
                ),
            )
            add(
                TourCard(
                    id = "home_customize",
                    chapter = TourChapter.HOME,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_home_customize_title),
                    content = { ctx, _ ->
                        TourLines(
                            if (ctx.hasSettings) stringResource(R.string.tour_home_customize) else null,
                            stringResource(R.string.tour_home_back),
                        )
                    },
                ),
            )
            add(
                TourCard(
                    id = TITLE,
                    chapter = TourChapter.TITLE,
                    depth = TourDepth.ESSENTIAL,
                    title = res(R.string.tour_title_title),
                    illustration = { TitleIllustration(it) },
                    content = { ctx, _ -> TitleCardText(ctx) },
                ),
            )
            add(
                TourCard(
                    id = "title_extras",
                    chapter = TourChapter.TITLE,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_title_extras_title),
                    isAvailable = { !it.isKid },
                    content = { _, _ ->
                        TourLines(
                            stringResource(R.string.tour_title_trailer),
                            stringResource(R.string.tour_title_watched),
                            stringResource(R.string.tour_title_rows),
                        )
                    },
                ),
            )
            add(
                TourCard(
                    id = "title_request",
                    chapter = TourChapter.TITLE,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_title_request_title),
                    isAvailable = { it.hasRequests },
                    content = { _, _ -> TourLines(stringResource(R.string.tour_title_request)) },
                ),
            )
            add(
                TourCard(
                    id = WATCHING,
                    chapter = TourChapter.WATCHING,
                    depth = TourDepth.ESSENTIAL,
                    title = res(R.string.tour_watch_title),
                    illustration = { PlayerIllustration(it) },
                    content = { ctx, _ -> WatchingCardText(ctx) },
                ),
            )
            add(
                TourCard(
                    id = "watch_more",
                    chapter = TourChapter.WATCHING,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_watch_more_title),
                    isAvailable = { !it.externalPlayer },
                    content = { ctx, _ ->
                        TourLines(
                            stringResource(R.string.tour_watch_skip_intro),
                            stringResource(R.string.tour_watch_next_up),
                            stringResource(R.string.tour_watch_gear),
                            if (ctx.hasSettings) stringResource(R.string.tour_watch_subtitle_style) else null,
                        )
                    },
                ),
            )
            add(
                TourCard(
                    id = SEARCH,
                    chapter = TourChapter.SEARCH,
                    depth = TourDepth.ESSENTIAL,
                    title = { ctx ->
                        stringResource(if (ctx.hasRequests) R.string.tour_search_requests_title else R.string.tour_search_title)
                    },
                    illustration = { SearchIllustration(it) },
                    content = { ctx, _ -> SearchCardText(ctx) },
                ),
            )
            add(
                TourCard(
                    id = "requests_more",
                    chapter = TourChapter.SEARCH,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_requests_more_title),
                    isAvailable = { it.hasRequests },
                    content = { ctx, _ ->
                        TourLines(
                            stringResource(R.string.tour_search_tabs, ctx.requestsLabel ?: ""),
                            stringResource(R.string.tour_requests_seasons),
                            stringResource(R.string.tour_requests_status),
                        )
                    },
                ),
            )
            add(
                TourCard(
                    id = "collections",
                    chapter = TourChapter.COLLECTIONS,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_collections_title),
                    isAvailable = { it.hasCollections },
                    content = { ctx, _ -> CollectionsCardText(ctx) },
                ),
            )
            add(
                TourCard(
                    id = "live_tv",
                    chapter = TourChapter.LIVE_TV,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_live_title),
                    isAvailable = { it.hasLiveTv },
                    content = { ctx, _ -> LiveTvCardText(ctx) },
                ),
            )
            add(
                TourCard(
                    id = SETTINGS,
                    chapter = TourChapter.SETTINGS,
                    depth = TourDepth.ESSENTIAL,
                    title = res(R.string.tour_settings_card_title),
                    // Kids have no Settings
                    isAvailable = { it.hasSettings },
                    content = { _, _ -> SettingsCardText() },
                ),
            )
            add(
                TourCard(
                    id = "settings_profiles",
                    chapter = TourChapter.SETTINGS,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_settings_profiles_title),
                    isAvailable = { it.hasSettings },
                    content = { _, _ ->
                        TourLines(
                            stringResource(R.string.tour_settings_pin),
                            stringResource(R.string.tour_settings_picture),
                            stringResource(R.string.tour_settings_people),
                        )
                    },
                ),
            )
            add(
                TourCard(
                    id = "settings_updates",
                    chapter = TourChapter.SETTINGS,
                    depth = TourDepth.DETAIL,
                    title = res(R.string.tour_settings_updates_title),
                    isAvailable = { it.hasSettings },
                    content = { _, _ ->
                        TourLines(
                            stringResource(R.string.tour_settings_updates),
                            stringResource(R.string.tour_settings_tour),
                        )
                    },
                ),
            )
        }

    /** The sign-in tour (essentials), or [chapter]'s essentials then its details; only what [ctx] has */
    fun visible(
        chapter: TourChapter?,
        ctx: TourContext,
    ): List<TourCard> =
        all.filter { card ->
            (if (chapter == null) card.depth == TourDepth.ESSENTIAL else card.chapter == chapter) && card.isAvailable(ctx)
        }

    /** Chapters the picker offers: those with at least one card for this person */
    fun chapters(ctx: TourContext): List<TourChapter> =
        TourChapter.entries.filter { it != TourChapter.PICKER && visible(it, ctx).isNotEmpty() }
}
