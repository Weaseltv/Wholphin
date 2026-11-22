package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.DiscoverRating
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.data.model.RemoteTrailer
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.CardRowAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.DiscoverItemCardView
import com.github.damontecres.wholphin.leanback.widget.card.RowScrollMode
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ItemDetailsDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuItem
import com.github.damontecres.wholphin.leanback.widget.text.DetailPageLayout
import com.github.damontecres.wholphin.leanback.widget.text.FILLED_STAR_COLOR
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.GenreTextView
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QUICK_DETAILS_DOT
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.appendIcon
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailActionHeight
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailRowHeight
import com.github.damontecres.wholphin.services.RequestsState
import com.github.damontecres.wholphin.services.TrailerService
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.discover.SeerrRequestsViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import java.util.Locale
import java.util.UUID

// Focus rows of the old detail pages (their `position`)
internal const val DISCOVER_HEADER_ROW = 0
internal const val DISCOVER_PEOPLE_ROW = 2
internal const val DISCOVER_SIMILAR_ROW = 4
internal const val DISCOVER_RECOMMENDED_ROW = 5

/**
 * A frame that measures its child at [fraction] of the width it is given (Compose `fillMaxWidth(fraction)`)
 */
internal class FractionWidthFrame(
    context: Context,
    private val fraction: Float,
) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val spec =
            if (mode == MeasureSpec.UNSPECIFIED) {
                widthMeasureSpec
            } else {
                MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) * fraction).toInt(), MeasureSpec.EXACTLY)
            }
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/**
 * `listToDotString` + `QuickDetailsText`: the parts with " • " between them, then " • 7.5★" and " • 85%🍅" (fresh at 60 %
 * and above), as one titleSmall onSurface text of at most two lines.
 */
internal fun quickDetailsLine(
    context: Context,
    parts: List<String>,
    communityRating: Float?,
    criticRating: Float?,
): CharSequence =
    SpannableStringBuilder().apply {
        parts.forEachIndexed { index, part ->
            append(part)
            if (index != parts.lastIndex) append(QUICK_DETAILS_DOT)
        }
        communityRating?.let {
            append(QUICK_DETAILS_DOT)
            append(String.format(Locale.getDefault(), "%.1f", it))
            appendIcon(context, R.drawable.lb_widget_ctl_ic_star, FILLED_STAR_COLOR)
        }
        criticRating?.let {
            append(QUICK_DETAILS_DOT)
            append("${it.toInt()}%")
            appendIcon(context, if (it >= 60f) R.drawable.ic_rotten_tomatoes_fresh else R.drawable.ic_rotten_tomatoes_rotten)
        }
    }

/**
 * The header of a Requests details page (`DiscoverMovieDetailsHeader` / `DiscoverSeriesDetailsHeader`), 4dp between
 * lines: the title or logo (75 % wide, 8dp in), then 60 % wide: the quick details, the genres, the tagline (bodyLarge
 * italic), the overview (3 lines; OK opens the details dialog off the WeaselTV theme) and "Directed by …".
 */
internal class DiscoverHeaderView(
    context: Context,
) : LinearLayout(context) {
    val titleOrLogo = TitleOrLogoView(context)
    val quickDetails =
        TextView(context).apply {
            applyTextRole(TextRole.TITLE_SMALL)
            setTextColor(AppTheme.palette.onSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    val genres = GenreTextView(context)
    val tagline =
        TextView(context).apply {
            applyTextRole(TextRole.BODY_LARGE)
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(AppTheme.palette.onSurface)
        }
    val overview =
        OverviewTextView(context).apply {
            maxLines = 3
        }
    val director =
        TextView(context).apply {
            applyTextRole(TextRole.BODY_MEDIUM)
            setTextColor(AppTheme.palette.onSurface)
        }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        val start = context.dp(HeaderDimens.START_PADDING)
        val titleFrame = FractionWidthFrame(context, .75f)
        titleFrame.addView(titleOrLogo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = start })
        addView(titleFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val info =
            LinearLayout(context).apply {
                orientation = VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        fun add(
            view: View,
            startPadding: Boolean,
            first: Boolean = false,
        ) = info.addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (startPadding) marginStart = start
                if (!first) topMargin = context.dp(4)
            },
        )
        add(quickDetails, startPadding = true, first = true)
        add(genres, startPadding = true)
        add(tagline, startPadding = true)
        add(overview, startPadding = false)
        add(director, startPadding = true)
        val infoFrame = FractionWidthFrame(context, .6f)
        infoFrame.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(infoFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
    }

    fun bind(
        title: String?,
        logoUrl: String?,
        showLogo: Boolean,
        details: CharSequence,
        genreNames: List<String>,
        taglineText: String?,
        overviewText: String?,
        directorName: String?,
        onOverviewClick: () -> Unit,
    ) {
        titleOrLogo.bind(title, logoUrl, showLogo)
        quickDetails.text = details
        genres.visibility = if (genreNames.isEmpty()) GONE else VISIBLE
        genres.setGenres(genreNames)
        tagline.visibility = if (taglineText == null) GONE else VISIBLE
        tagline.text = taglineText
        overview.visibility = if (overviewText == null) GONE else VISIBLE
        overview.setText(overviewText)
        overview.onClick = onOverviewClick
        wrapOverview()
        director.visibility = if (directorName == null) GONE else VISIBLE
        director.text = directorName?.let { context.getString(R.string.directed_by, it) }
    }

    /** The detail header became compact (or not) */
    fun setCompact(compact: Boolean) {
        titleOrLogo.compactHeader = compact
        genres.compact = compact
        overview.compact = compact
        wrapOverview()
    }

    /** `textBoxHeight = Dp.Unspecified`: the overview box is as tall as its text on every theme */
    private fun wrapOverview() {
        val lp = overview.textView.layoutParams
        if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            overview.textView.layoutParams = lp
        }
    }
}

/** What the request buttons show */
internal data class DiscoverButtonsState(
    val tmdbId: Int?,
    /** "movie" or "tv" */
    val mediaType: String,
    val availability: SeerrAvailability,
    val canRequest: Boolean,
    val canCancel: Boolean,
    val trailers: List<Trailer>,
)

/**
 * `ExpandableDiscoverButtons`: 16dp apart with 24dp side and 8dp top / bottom padding (the action bar reports its
 * height to the header). The first button follows the title's Requests state: Request, Pending (the request's stage
 * when the user has one), Go to (available), Unavailable / Delete (disabled); Request again for partly available
 * titles and shows with a request in progress; Cancel for the user's pending request; the trailer button. Entering
 * the row from above or below returns to the button focused last (the first at first).
 */
internal class DiscoverButtonsRow(
    context: Context,
    private val actions: Actions,
) : FocusRestoreRow(context) {
    /** What the buttons do (the old callbacks) */
    interface Actions {
        fun request()

        fun cancel()

        fun goTo()

        fun playTrailer(trailer: Trailer)

        fun openRequest(
            requestId: Int,
            scope: String?,
        )

        fun showQueue(requestId: Int?)
    }

    val first: AppButton = expandableFaButton(context, R.string.request, R.string.fa_download)
    private val partial = expandableFaButton(context, R.string.request, R.string.fa_download)
    private val cancel = expandableIconButton(context, R.string.cancel, R.drawable.lb_widget_ctl_ic_delete)
    private val trailer = expandableFaButton(context, R.string.no_trailers, R.string.fa_film)
    private var state: DiscoverButtonsState? = null
    private var requests: RequestsState = RequestsState()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        // 8dp under the buttons plus the 16dp the page put below the row (part of the reported action bar height)
        setPaddingRelative(context.dp(24), context.dp(8), context.dp(24), context.dp(8 + 16))
        listOf(first, partial, cancel, trailer).forEachIndexed { index, button ->
            addView(
                button,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = context.dp(16)
                },
            )
        }
        defaultFocus = first
        reportDetailActionHeight()
        partial.setOnClickListener { actions.request() }
        cancel.setOnClickListener {
            first.requestFocus()
            actions.cancel()
        }
    }

    fun bind(state: DiscoverButtonsState) {
        this.state = state
        render()
    }

    fun setRequests(requests: RequestsState) {
        this.requests = requests
        render()
    }

    private fun render() {
        val s = state ?: return
        val neon = AppTheme.isNeon
        val matching = requests.items.filter { it.request.media?.tmdbId == s.tmdbId && it.request.type == s.mediaType }
        val ownRequest =
            matching.firstOrNull { !it.stage.terminal && it.request.is4k != true }
                ?: matching.firstOrNull { !it.stage.terminal }
                ?: matching.firstOrNull()
        val (text, glyph) =
            when (s.availability) {
                SeerrAvailability.UNKNOWN -> R.string.request to R.string.fa_download
                SeerrAvailability.PENDING, SeerrAvailability.PROCESSING -> R.string.pending to R.string.fa_clock
                SeerrAvailability.PARTIALLY_AVAILABLE, SeerrAvailability.AVAILABLE -> R.string.go_to to R.string.fa_play
                SeerrAvailability.DELETED -> R.string.delete to R.string.fa_video
                SeerrAvailability.BLOCKLISTED -> R.string.unavailable to R.string.fa_xmark
            }
        val label = ownRequest?.stage?.label ?: context.getString(text)
        first.setLabel(label, allCaps = neon)
        first.contentDescription = label
        first.setGlyph(glyph, sizeSp = 16f, boxDp = 32f)
        first.isActionEnabled = ownRequest != null ||
            when (s.availability) {
                SeerrAvailability.UNKNOWN -> s.canRequest
                SeerrAvailability.PENDING, SeerrAvailability.PROCESSING, SeerrAvailability.PARTIALLY_AVAILABLE,
                SeerrAvailability.AVAILABLE,
                -> true
                SeerrAvailability.BLOCKLISTED, SeerrAvailability.DELETED -> false
            }
        first.setOnClickListener {
            if (ownRequest != null) {
                if (ownRequest.stage.ready && ownRequest.jellyfinId != null) {
                    actions.openRequest(ownRequest.request.id, requests.scope)
                } else {
                    actions.showQueue(ownRequest.request.id)
                }
            } else {
                when (s.availability) {
                    SeerrAvailability.UNKNOWN -> actions.request()
                    SeerrAvailability.PENDING, SeerrAvailability.PROCESSING -> actions.showQueue(null)
                    SeerrAvailability.PARTIALLY_AVAILABLE, SeerrAvailability.AVAILABLE -> actions.goTo()
                    SeerrAvailability.DELETED, SeerrAvailability.BLOCKLISTED -> {}
                }
            }
        }
        val showPartial =
            s.availability == SeerrAvailability.PARTIALLY_AVAILABLE ||
                (
                    s.mediaType == "tv" &&
                        (
                            s.availability == SeerrAvailability.PENDING || s.availability == SeerrAvailability.PROCESSING ||
                                ownRequest?.stage?.terminal == false
                        )
                )
        partial.visibility = if (showPartial) VISIBLE else GONE
        partial.isActionEnabled = s.canRequest
        cancel.visibility = if (s.canCancel) VISIBLE else GONE
        cancel.isActionEnabled = s.canCancel
        val trailers = s.trailers
        val trailerLabel =
            context.getString(
                when {
                    trailers.isEmpty() -> R.string.no_trailers
                    trailers.size == 1 -> R.string.play_trailer
                    else -> R.string.trailers_title
                },
            )
        trailer.setLabel(trailerLabel, allCaps = neon)
        trailer.contentDescription = trailerLabel
        trailer.isActionEnabled = trailers.isNotEmpty()
        trailer.setOnClickListener { view ->
            if (trailers.size == 1) {
                actions.playTrailer(trailers.first())
            } else {
                showTrailerDialog(view, trailers)
            }
        }
        // A button that went away cannot keep focus
        if ((partial.hasFocus() && !showPartial) || (cancel.hasFocus() && !s.canCancel)) first.requestFocus()
    }

    /** `TrailerDialog`: the trailers with "Local" or their source as supporting text */
    private fun showTrailerDialog(
        opener: View,
        trailers: List<Trailer>,
    ) {
        val local = context.getString(R.string.local)
        val external = context.getString(R.string.external_track)
        MenuDialog(
            context,
            context.getString(R.string.play_trailer),
            trailers.map { trailer ->
                MenuItem(
                    headline = trailer.name,
                    supporting =
                        when (trailer) {
                            is LocalTrailer -> local
                            is RemoteTrailer -> trailer.subtitle ?: external
                        },
                    onClick = { actions.playTrailer(trailer) },
                )
            },
            waitToLoad = false,
            opener = opener,
        ).show()
    }
}

/**
 * The shared page of the Requests movie and show details (`DiscoverMovieDetailsContent` /
 * `DiscoverSeriesDetailsContent`): loading, error, or the detail page: the header fitted above the first row, the
 * request buttons, then People, More like this and Recommended (Requests cards; OK opens their Requests page).
 *
 * First focus is the first request button once the page has loaded. The page scrolls only as far as needed on the
 * WeaselTV theme (`KeepVisibleBringIntoViewSpec`) and pivots elsewhere; focusing a button brings the whole header
 * into view.
 */
abstract class DiscoverDetailsFragment : DestinationFragment() {
    protected val requestsViewModel: SeerrRequestsViewModel by viewModels()
    protected val errorViewModel: ErrorViewModel by viewModels()

    /** Movie: 32dp side padding and 16dp between rows (8dp above and below); show: none (80dp below) */
    protected abstract val isMovie: Boolean

    private lateinit var root: FrameLayout
    private var stateView: View? = null
    private var page: DetailPageLayout? = null
    private lateinit var list: PageRowsView
    internal lateinit var header: DiscoverHeaderView
        private set
    internal lateinit var buttons: DiscoverButtonsRow
        private set
    private lateinit var headerBlock: LinearLayout
    private var peopleRow: CardRowView? = null
    private var similarRow: CardRowView? = null
    private var recommendedRow: CardRowView? = null
    private val similarAdapter = discoverAdapter()
    private val recommendedAdapter = discoverAdapter()
    private var shownPeople: List<DiscoverItem>? = null
    private var focusedOnce = false

    private fun discoverAdapter(): CardRowAdapter<DiscoverItem> =
        CardRowAdapter(
            cardPresenter<DiscoverItem, DiscoverItemCardView>(::DiscoverItemCardView) { card, item, _, _ ->
                card.bind(item, pageStyle, showOverlay = true)
            },
            CardCallbacks(onClick = { _, item -> navigateTo(Destination.DiscoveredItem(item)) }, onLongClick = { _, _ -> }),
        )

    /** Navigate (through the page's view model) */
    protected abstract fun navigateTo(destination: Destination)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root =
            FrameLayout(requireContext()).apply {
                clipChildren = false
                clipToPadding = false
            }
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requestsViewModel.requests.state.collectWhileStarted { state ->
            if (page != null) buttons.setRequests(state)
        }
    }

    override fun onDestroyView() {
        page = null
        stateView = null
        super.onDestroyView()
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (restoring || page == null) {
            super.onScreenFocus(restoring)
            return
        }
        buttons.first.requestFocus()
    }

    protected fun showLoading() {
        if (stateView is LoadingPageView) return
        clearState()
        stateView = LoadingPageView(requireContext()).also { root.addView(it, matchParams()) }
    }

    protected fun showError(error: DataLoadingState.Error) {
        clearState()
        stateView = ErrorView(requireContext(), error, errorViewModel::sendLogs).also { root.addView(it, matchParams()) }
        if (isTopScreen) requestInitialFocus(stateView!!)
    }

    private fun clearState() {
        stateView?.let { root.removeView(it) }
        stateView = null
        page?.let { root.removeView(it) }
        page = null
    }

    private fun matchParams() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** The detail page exists (built on the first successful load) */
    protected fun ensurePage() {
        if (page != null) return
        stateView?.let { root.removeView(it) }
        stateView = null
        val context = requireContext()
        val side = if (isMovie) context.dp(32) else 0
        val layout = DetailPageLayout(context)
        list =
            PageRowsView(context, keepVisible = AppTheme.isNeon).apply {
                if (isMovie) setPadding(0, context.dp(8), 0, context.dp(8)) else setPadding(0, 0, 0, context.dp(80))
            }
        layout.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).bleedIntoGutters(context))
        header = DiscoverHeaderView(context)
        val fitted =
            FittedDetailHeaderLayout(context).apply {
                onCompactChanged = { compact -> header.setCompact(compact) }
                addView(header)
            }
        header.setPadding(0, context.dp(HeaderDimens.detailTopPadding()), 0, context.dp(8))
        buttons = DiscoverButtonsRow(context, buttonActions())
        headerBlock =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPaddingRelative(context.gutterStartPx() + side, 0, context.gutterEndPx() + side, 0)
                addView(fitted, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        root.addView(layout, matchParams())
        page = layout
        buttons.setRequests(requestsViewModel.requests.state.value)
    }

    private fun rowStyle(): CardRowStyle {
        val side = if (isMovie) requireContext().dp(32) else 0
        return CardRowStyle(
            scrollMode = RowScrollMode.forDetailPage(),
            contentInsetStartPx = requireContext().gutterStartPx() + side,
            contentInsetEndPx = requireContext().gutterEndPx() + side,
        )
    }

    /** Show the rows below the header (only those with items) */
    protected fun bindRows(
        people: List<DiscoverItem>,
        similar: List<DiscoverItem>,
        recommended: List<DiscoverItem>,
    ) {
        val context = requireContext()
        val side = if (isMovie) context.dp(32) else 0
        val shown = mutableListOf<View>(headerBlock)
        if (people.isNotEmpty()) {
            val row =
                peopleRow ?: CardRowView(context).also {
                    it.reportDetailRowHeight(DISCOVER_PEOPLE_ROW)
                    peopleRow = it
                }
            if (people != shownPeople) {
                shownPeople = people
                row.showDiscoverPeople(
                    people,
                    pageStyle,
                    onClick = { navigateTo(Destination.DiscoveredItem(it)) },
                    onLongClick = { _, _ -> },
                    style =
                        CardRowStyle.personRow(
                            contentInsetStartPx = context.gutterStartPx() + side,
                            contentInsetEndPx = context.gutterEndPx() + side,
                        ),
                )
            }
            shown += row
        }
        fun itemRow(
            existing: CardRowView?,
            title: Int,
            priority: Int,
            adapter: CardRowAdapter<DiscoverItem>,
            items: List<DiscoverItem>,
        ): CardRowView {
            val row = existing ?: CardRowView(context).also { it.reportDetailRowHeight(priority) }
            row.setup(getString(title), pageStyle.sectionAccent, pageStyle, rowStyle())
            row.setCards(adapter, items)
            return row
        }
        if (similar.isNotEmpty()) {
            similarRow = itemRow(similarRow, R.string.more_like_this, DISCOVER_SIMILAR_ROW, similarAdapter, similar)
            shown += similarRow!!
        }
        if (recommended.isNotEmpty()) {
            recommendedRow = itemRow(recommendedRow, R.string.recommended, DISCOVER_RECOMMENDED_ROW, recommendedAdapter, recommended)
            shown += recommendedRow!!
        }
        val spacing = if (isMovie) context.dp(16) else 0
        shown.forEachIndexed { index, view ->
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = if (index == 0) 0 else spacing
            view.layoutParams = lp
        }
        list.setRows(shown)
        if (!focusedOnce) {
            focusedOnce = true
            // The old page focused its saved row (the buttons) once its content was composed
            list.selectRow(headerBlock) {
                if (isTopScreen && view?.hasFocus() != true) buttons.first.requestFocus()
            }
        }
    }

    override fun onPageStyleChanged(style: PageStyle) {
        similarAdapter.notifyDataSetChanged()
        recommendedAdapter.notifyDataSetChanged()
        shownPeople = null
    }

    /** The logo of the library item Seerr knows, when there is one */
    protected fun logoUrl(jellyfinId: UUID?): String? =
        jellyfinId?.let { CardImages.imageUrlService(requireContext()).getItemImageUrl(it, org.jellyfin.sdk.model.api.ImageType.LOGO) }

    protected fun showOverview(
        title: String?,
        overview: String?,
        genres: List<String>,
    ) {
        ItemDetailsDialog(
            requireContext(),
            com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo(
                title = title ?: getString(R.string.unknown),
                overview = overview,
                genres = genres,
                files = listOf(),
            ),
            showFilePath = false,
            opener = view?.findFocus(),
        ).show()
    }

    protected fun playTrailer(trailer: Trailer) {
        TrailerService.onClick(requireContext(), trailer, ::navigateTo)
    }

    /** Ratings into the quick details: audience (stars) and critics (tomatoes) */
    protected fun ratingsLine(
        parts: List<String>,
        rating: DiscoverRating?,
    ): CharSequence = quickDetailsLine(requireContext(), parts, rating?.audienceRating, rating?.criticRating?.toFloat())

    /** The actions of the request buttons */
    internal abstract fun buttonActions(): DiscoverButtonsRow.Actions

    /** The buttons' "open the request queue" (Requests page on the request) */
    protected fun showQueue(requestId: Int?) {
        requestsViewModel.navigation.navigateToFromDrawer(Destination.Discover(1, requestId))
    }
}
