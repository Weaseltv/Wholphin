package com.github.damontecres.wholphin.leanback.home

import android.content.Context
import android.view.ViewGroup
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.forHomeRowCard
import com.github.damontecres.wholphin.leanback.theme.libraryAccent
import com.github.damontecres.wholphin.leanback.theme.streamingProviderAccent
import com.github.damontecres.wholphin.leanback.theme.streamingProviderBorderAccent
import com.github.damontecres.wholphin.leanback.theme.typeAccent
import com.github.damontecres.wholphin.leanback.widget.card.BannerCardView
import com.github.damontecres.wholphin.leanback.widget.card.BaseCardView
import com.github.damontecres.wholphin.leanback.widget.card.CardImageScale
import com.github.damontecres.wholphin.leanback.widget.card.CardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.GenreCardView
import com.github.damontecres.wholphin.leanback.widget.card.StudioCardView
import com.github.damontecres.wholphin.leanback.widget.card.ViewMoreCardView
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.services.WeaselHomeRows
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

/*
 * The plain parts of Home's rows (no-Compose UI): row accents and headings, the card each item gets
 * (`HomePageCardContent`) and the View more card (`HomePageViewMoreCard`). Same rules as the old ui/main/HomePage.kt.
 */

/** Continue Watching, Next Up or the two combined: OK on a card may play it straight away (click to play) */
val HomeRowLoadingState?.isWatchingRow: Boolean
    get() {
        val type = (this as? HomeRowLoadingState.Success)?.rowType
        return type is HomeRowConfig.ContinueWatching || type is HomeRowConfig.NextUp ||
            type is HomeRowConfig.ContinueWatchingCombined
    }

/** Whether [row] is the Streaming Services row */
internal fun isStreamingRow(row: HomeRowConfig?): Boolean = row?.let(StreamingCollections::isStreamingRow) == true

/**
 * `homeRowAccent`: resolve the row's source library, so named sections such as Boxing keep their rail colour.
 * [fallback] is the page's section accent (`LocalNeonAccent`).
 */
fun homeRowAccentFor(
    row: HomeRowConfig?,
    libraries: List<Library>,
    fallback: Int,
): Int {
    if (isStreamingRow(row)) return NeonSections.Collections.border
    if (row is HomeRowConfig.JustWatch) {
        return if (row.list.shows) NeonSections.TvShows.border else NeonSections.Movies.border
    }
    if (row is HomeRowConfig.RecentlyAddedCombined) {
        when (row.collectionType) {
            CollectionType.MOVIES -> {
                return NeonSections.Movies.border
            }

            CollectionType.TVSHOWS -> {
                return NeonSections.TvShows.border
            }

            else -> {}
        }
    }
    val parentId =
        when (row) {
            is HomeRowConfig.RecentlyAdded -> row.parentId
            is HomeRowConfig.RecentlyReleased -> row.parentId
            is HomeRowConfig.Genres -> row.parentId
            is HomeRowConfig.Studios -> row.parentId
            is HomeRowConfig.Suggestions -> row.parentId
            is HomeRowConfig.ByParent -> row.parentId
            is HomeRowConfig.GetItems -> row.getItems.parentId
            else -> null
        }
    val library = libraries.firstOrNull { it.itemId == parentId }
    return library?.let { libraryAccent(it.name, it.collectionType) } ?: fallback
}

/** `homeRowHeading`: split Home headings (title, kicker) from row semantics rather than parsing a translated title */
fun homeRowHeading(
    context: Context,
    row: HomeRowConfig?,
    libraries: List<Library>,
    fallback: String,
): Pair<String, String?> =
    when {
        row is HomeRowConfig.ContinueWatchingCombined -> {
            context.getString(R.string.continue_watching) to "${context.getString(R.string.next_up)} &"
        }

        isStreamingRow(row) -> {
            StreamingCollections.NAME to context.getString(R.string.collections)
        }

        row is HomeRowConfig.RecentlyAdded -> {
            libraries.firstOrNull { it.itemId == row.parentId }?.let { library ->
                library.name to context.getString(R.string.home_new_in)
            } ?: (fallback to null)
        }

        row is HomeRowConfig.RecentlyReleased -> {
            libraries.firstOrNull { it.itemId == row.parentId }?.name?.let {
                it to context.getString(R.string.recently_released)
            } ?: (fallback to null)
        }

        row is HomeRowConfig.RecentlyAddedCombined -> {
            context.getString(WeaselHomeRows.recentlyAddedHeading(row)) to context.getString(R.string.home_recently_added_kicker)
        }

        row is HomeRowConfig.JustWatch -> {
            context.getString(row.list.title) to context.getString(row.list.kicker)
        }

        else -> {
            fallback to null
        }
    }

/**
 * The accent a card of [item] wears in a Home row of type [row] (`LocalHomeRowAccent` around `HomePageCardContent`):
 * Continue Watching takes the item's library colour, Streaming Services the provider's, media items their type colour
 * (except in Stand Up Comedy and Sports rows), anything else the row's accent.
 */
fun homeCardAccent(
    row: HomeRowConfig?,
    item: BaseItem?,
    libraries: List<Library>,
    fallback: Int,
): Int {
    val rowAccent = { homeRowAccentFor(row, libraries, fallback) }
    return if (item != null && (row is HomeRowConfig.ContinueWatching || row is HomeRowConfig.ContinueWatchingCombined)) {
        libraries.firstOrNull { it.itemId == item.libraryId }?.let { libraryAccent(it.name, it.collectionType) }
            ?: typeAccent(item.type)
    } else if (item != null && isStreamingRow(row)) {
        streamingProviderAccent(item.name) ?: rowAccent()
    } else if (item?.type in MEDIA_KINDS) {
        val rowColor = rowAccent()
        if (rowColor == NeonSections.StandUpComedy.border || rowColor == NeonSections.Sports.border) rowColor else typeAccent(item?.type)
    } else {
        rowAccent()
    }
}

private val MEDIA_KINDS = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)

/** `LocalHomeCardBorderAccent`: streaming providers' contrast border */
fun homeCardBorderAccent(
    row: HomeRowConfig?,
    item: BaseItem?,
): Int? = if (isStreamingRow(row)) streamingProviderBorderAccent(item?.name) else null

/** Rows mixing movies and shows (only the older Recently Added row) say which each card is */
internal val HomeRowConfig?.mixesMoviesAndShows: Boolean
    get() = this is HomeRowConfig.RecentlyAddedCombined && collectionType == null

/** MOVIE or SHOW; an episode or season stands for its show */
internal fun mediaKindBadge(
    context: Context,
    kind: BaseItemKind?,
): String? =
    when (kind) {
        BaseItemKind.MOVIE -> context.getString(R.string.kind_movie)
        BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE -> context.getString(R.string.show)
        else -> null
    }

/**
 * The cards of one Home row (`HomePageCardContent`): genre and studio tiles at the row height, every other item a
 * banner card (with its title below when the row shows titles), sized, cropped and badged by the row's view options.
 * Each card's focus look takes the row's card appearance, its own accent and the streaming border override.
 */
class HomeCardPresenter : CardPresenter<BaseItem>() {
    var pageStyle: PageStyle = PageStyle()
    var viewOptions: HomeRowViewOptions = HomeRowViewOptions()
    var rowType: HomeRowConfig? = null
    var libraries: List<Library> = emptyList()

    /** Streaming rows key cards by item, so focus follows a card being reordered; others by index */
    var keyByItem: Boolean = false

    /** Scale of the corner text (episode number / unwatched count) */
    var cornerTextScale: Float = 1f

    override fun viewType(
        item: BaseItem?,
        position: Int,
    ): Int =
        when (item?.type) {
            BaseItemKind.GENRE -> TYPE_GENRE
            BaseItemKind.STUDIO -> TYPE_STUDIO
            else -> TYPE_BANNER
        }

    override fun create(
        parent: ViewGroup,
        viewType: Int,
    ): BaseCardView =
        when (viewType) {
            TYPE_GENRE -> GenreCardView(parent.context)
            TYPE_STUDIO -> StudioCardView(parent.context)
            else -> BannerCardView(parent.context)
        }

    override fun key(
        item: BaseItem?,
        position: Int,
    ): Any = if (keyByItem) item?.id ?: position else position

    override fun bind(
        card: BaseCardView,
        item: BaseItem?,
        position: Int,
        cellWidthPx: Int,
    ) {
        val context = card.context
        val options = viewOptions
        val cardStyle =
            pageStyle.forHomeRowCard(
                options,
                homeCardAccent(rowType, item, libraries, pageStyle.sectionAccent),
                homeCardBorderAccent(rowType, item),
            )
        when (card) {
            is GenreCardView -> {
                card.bind(item?.id, item?.name, item?.imageUrlOverride, cardStyle, heightPx = context.dp(options.heightDp))
            }

            is StudioCardView -> {
                card.bind(item?.id, item?.name, item?.imageUrlOverride, cardStyle, heightPx = context.dp(options.heightDp))
            }

            is BannerCardView -> {
                val episode = item?.type == BaseItemKind.EPISODE
                val imageType = if (episode) options.episodeImageType.imageType else options.imageType.imageType
                val ratio = if (episode) options.episodeAspectRatio.ratio else options.aspectRatio.ratio
                val scale = if (episode) options.episodeContentScale else options.contentScale
                val withTitle = options.showTitles
                card.bind(
                    item = item,
                    style = cardStyle,
                    name = item?.data?.seriesName ?: item?.name,
                    cardHeightDp = options.heightDp.toFloat(),
                    aspectRatio = ratio,
                    cornerText = item?.ui?.episodeUnplayedCornerText,
                    played = item?.data?.userData?.played ?: false,
                    favorite = item?.favorite ?: false,
                    playPercent = item?.data?.userData?.playedPercentage ?: 0.0,
                    imageType = imageType,
                    imageScale = CardImageScale.from(scale),
                    useSeriesForPrimary = options.useSeries,
                    // BannerCardWithTitle had no corner text scale nor type badge
                    cornerTextScale = if (withTitle) 1f else cornerTextScale,
                    typeBadge =
                        if (!withTitle && rowType.mixesMoviesAndShows) mediaKindBadge(context, item?.type) else null,
                    withTitle = withTitle,
                    title = item?.title,
                    subtitle = item?.subtitle,
                )
            }
        }
    }

    /** `HomePageViewMoreCard`: sized like the row's cards (episode ratio when the row ends with an episode) */
    fun bindViewMore(
        card: ViewMoreCardView,
        isEpisode: Boolean,
    ) {
        val options = viewOptions
        val aspectRatio = if (isEpisode) options.episodeAspectRatio else options.aspectRatio
        val style = pageStyle.forHomeRowCard(options, homeRowAccentFor(rowType, libraries, pageStyle.sectionAccent))
        card.bind(
            style = style,
            aspectRatio = aspectRatio.ratio,
            heightPx = card.context.dp(options.heightDp),
            showTitle = options.showTitles,
        )
    }

    private companion object {
        const val TYPE_BANNER = 0
        const val TYPE_GENRE = 1
        const val TYPE_STUDIO = 2
    }
}
