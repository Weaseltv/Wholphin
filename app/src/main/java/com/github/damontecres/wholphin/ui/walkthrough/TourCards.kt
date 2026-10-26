package com.github.damontecres.wholphin.ui.walkthrough

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
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
    @param:StringRes val title: Int,
    val isAvailable: (TourContext) -> Boolean = { true },
    /** The live menu step: text only, and Previous is hidden so Next is the leftmost button */
    val liveMenuStep: Boolean = false,
    val illustration: (@Composable (TourContext) -> Unit)? = null,
    val content: @Composable (TourContext, TourCardState) -> Unit,
)

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

    /** Every card, in tour order. Essentials make up the sign-in tour; a chapter shows its essentials, then details. */
    val all: List<TourCard> =
        listOf(
            TourCard(
                id = REMOTE,
                chapter = TourChapter.REMOTE,
                depth = TourDepth.ESSENTIAL,
                title = R.string.tour_remote_title,
                illustration = { RemoteIllustration() },
                content = { ctx, _ -> RemoteCardText(ctx) },
            ),
            TourCard(
                id = MENU_LIVE,
                chapter = TourChapter.MENU,
                depth = TourDepth.ESSENTIAL,
                title = R.string.tour_menu_try_title,
                liveMenuStep = true,
                content = { ctx, state -> MenuTryCardText(ctx, state) },
            ),
            TourCard(
                id = MENU_WHERE,
                chapter = TourChapter.MENU,
                depth = TourDepth.ESSENTIAL,
                title = R.string.tour_where_title,
                content = { ctx, _ -> WhereEverythingIsText(ctx) },
            ),
        )

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
