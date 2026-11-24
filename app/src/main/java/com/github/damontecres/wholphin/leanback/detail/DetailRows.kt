package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardPadding
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.DiscoverItemCardView
import com.github.damontecres.wholphin.leanback.widget.card.RowScrollMode
import com.github.damontecres.wholphin.leanback.widget.card.RowTitleStyle
import com.github.damontecres.wholphin.leanback.widget.card.SeasonCardView
import com.github.damontecres.wholphin.leanback.widget.card.ViewMoreSpec
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.text.DetailPageLayout
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailRowHeight

/*
 * Rows and page scaffolding shared by the details pages.
 */

/** `ItemRow` on a details page: its default look, keep-visible scrolling, spanning the page gutters */
internal fun detailItemRowStyle(context: Context): CardRowStyle =
    CardRowStyle(
        scrollMode = RowScrollMode.forDetailPage(),
        contentInsetStartPx = context.neonGutterStartPx(),
        contentInsetEndPx = context.neonGutterEndPx(),
    )

/** `PersonRow` on a details page */
internal fun detailPersonRowStyle(context: Context): CardRowStyle =
    CardRowStyle.personRow(contentInsetStartPx = context.neonGutterStartPx(), contentInsetEndPx = context.neonGutterEndPx())

/** `ChapterRow` on a details page */
internal fun detailChapterRowStyle(context: Context): CardRowStyle =
    CardRowStyle.chapterRow(contentInsetStartPx = context.neonGutterStartPx(), contentInsetEndPx = context.neonGutterEndPx())

/**
 * `DiscoverItemRow` (the Requests "Discover" row; never shown in the weaselfin flavor): the title 16dp in on the
 * WeaselTV theme (else 8dp), 8dp to the cards, cards 16dp apart with 16 / 8dp padding, no count.
 */
internal fun detailDiscoverRowStyle(context: Context): CardRowStyle =
    CardRowStyle(
        spacingDp = 16f,
        cardPadding = CardPadding(16f, 16f, 8f, 8f),
        dividerGapDp = 8f,
        title = RowTitleStyle(titleStartPaddingDp = if (AppTheme.isNeon) 16f else 8f),
        showCount = false,
        scrollMode = RowScrollMode.forDetailPage(),
        contentInsetStartPx = context.neonGutterStartPx(),
        contentInsetEndPx = context.neonGutterEndPx(),
        stripBleedStart = false,
    )

/** A details page's row view, with its height reported to the detail viewport ([priority] picks the first row) */
internal fun detailCardRow(
    context: Context,
    priority: Int = Int.MAX_VALUE,
): CardRowView =
    CardRowView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        reportDetailRowHeight(priority)
    }

/**
 * Season cards for an item row ("More like this", seasons): the poster [imageHeightDp] tall, natural width, overlay
 * on, each in its own type accent.
 */
internal class SeasonRowBinder(
    private var pageStyle: PageStyle,
    private var imageHeightDp: Float,
) {
    val presenter =
        cardPresenter<BaseItem, SeasonCardView>(::SeasonCardView) { card, item, _, _ ->
            card.bind(item, pageStyle, imageHeightDp = imageHeightDp, showImageOverlay = true)
        }

    fun update(
        pageStyle: PageStyle,
        imageHeightDp: Float,
    ) {
        this.pageStyle = pageStyle
        this.imageHeightDp = imageHeightDp
    }
}

/** The Discover row's cards and callbacks (`DiscoverRow` with loaded items) */
internal class DiscoverRowBinder(
    private var pageStyle: PageStyle,
    onClick: (Int, DiscoverItem) -> Unit,
) {
    val adapter =
        CardRowAdapter(
            cardPresenter<DiscoverItem, DiscoverItemCardView>(::DiscoverItemCardView) { card, item, _, _ ->
                card.bind(item, pageStyle)
            },
            CardCallbacks(onClick = onClick, onLongClick = { _, _ -> }),
        )

    fun bind(
        row: CardRowView,
        pageStyle: PageStyle,
        titleAccent: Int,
        items: List<DiscoverItem>,
        viewMore: ViewMoreSpec? = null,
    ) {
        this.pageStyle = pageStyle
        row.setup(row.context.getString(R.string.discover), titleAccent, pageStyle, detailDiscoverRowStyle(row.context))
        row.setCards(adapter, items, viewMore)
    }
}

/**
 * The content of a details page: the detail viewport ([DetailPageLayout]) holding the page list, which spans the
 * WeaselTV gutters (rows reach them; headers pad themselves back to the content edge with [headerPadding]).
 */
internal class DetailPageContent(
    context: Context,
    spacingDp: Float = 16f,
    bottomPaddingDp: Float = 8f,
    topPaddingDp: Float = 0f,
) {
    val viewport = DetailPageLayout(context)
    val list = DetailPageList(context, spacingDp)

    init {
        list.setPadding(0, context.dp(topPaddingDp), 0, context.dp(bottomPaddingDp))
        viewport.addView(
            list,
            android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = -context.neonGutterStartPx()
                marginEnd = -context.neonGutterEndPx()
            },
        )
    }

    /** Put a header block back at the page's content edge */
    fun headerPadding(view: View) {
        val c = view.context
        view.setPaddingRelative(c.neonGutterStartPx(), view.paddingTop, c.neonGutterEndPx(), view.paddingBottom)
    }

    /** Run [block] once the list has laid out its items (eg to focus the Play button the first time) */
    fun afterLayout(block: () -> Unit) {
        list.doOnPreDraw { block() }
    }
}

/** A vertical block of views (a page list item made of several pieces, eg the header and its buttons) */
internal fun verticalBlock(
    context: Context,
    spacingDp: Float = 0f,
): LinearLayout =
    FractionColumn(context, 1f, spacingDp).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

/** The poster height of "More like this" cards: 2:3 posters for movies, episode height otherwise */
internal fun similarImageHeightDp(item: BaseItem): Float =
    if (item.type == org.jellyfin.sdk.model.api.BaseItemKind.MOVIE) {
        CardDimens.HEIGHT_2X3_DP.toFloat()
    } else {
        CardDimens.HEIGHT_EPISODE_DP.toFloat()
    }
