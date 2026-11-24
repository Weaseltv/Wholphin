package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.widget.card.CardCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.card.PlaceholderRowView
import com.github.damontecres.wholphin.leanback.widget.card.SeasonCardView
import com.github.damontecres.wholphin.leanback.widget.card.ViewMoreSpec
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.loadImageUrl
import com.github.damontecres.wholphin.leanback.widget.text.FittedDetailHeaderLayout
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailRowHeight
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.PersonState
import com.github.damontecres.wholphin.ui.detail.PersonViewModel
import com.github.damontecres.wholphin.ui.detail.rowTitle
import com.github.damontecres.wholphin.ui.formatDate
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.RowLoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.jellyfin.sdk.model.api.ImageType
import java.time.LocalDate
import kotlin.math.min

private const val MOVIE_ROW = 1
private const val SERIES_ROW = 2
private const val EPISODE_ROW = 3

/**
 * The person page (PersonPage.kt): the header (picture, then a panel with the name, born / birthplace / died, the
 * overview and the Watchlist button), then the Movies (n), TV shows (n) and Episodes (n) rows of their credits (each
 * hidden when empty, "Loading…" or the error while loading), and (other flavors) Discover with View more.
 *
 * Focus starts in the header (the overview on other themes, else the Watchlist button) and comes back to where it was.
 */
@AndroidEntryPoint
class PersonPageFragment : DetailPageFragment() {
    private val viewModel: PersonViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<PersonViewModel.Factory> {
                it.create(requireDestination<Destination.MediaItem>().itemId)
            }
        },
    )

    private var page: PersonPage? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        page = null
        viewModel.state.collectWhileStarted { render(it) }
    }

    override fun onDestroyView() {
        page = null
        super.onDestroyView()
    }

    override fun onPageStyleChanged(style: PageStyle) {
        render(viewModel.state.value)
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) page?.firstFocus()?.let { if (it.isAttachedToWindow && it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    private fun render(state: PersonState) {
        when (val st = state.person) {
            is DataLoadingState.Error -> {
                showError(st)
            }

            DataLoadingState.Loading, DataLoadingState.Pending -> {
                showLoading()
            }

            is DataLoadingState.Success -> {
                var created: PersonPage? = null
                val first =
                    showContent {
                        PersonPage(requireContext())
                            .also {
                                created = it
                                page = it
                            }.content.viewport
                    }
                page?.bind(st.data, state)
                if (first) created?.let { c -> c.content.afterLayout { c.firstFocus()?.let(::requestInitialFocus) } }
            }
        }
    }

    private inner class PersonPage(
        context: Context,
    ) {
        val content = DetailPageContent(context, spacingDp = 16f, bottomPaddingDp = 0f)
        private val header = PersonHeaderView(context)
        private val fitted =
            FittedDetailHeaderLayout(context, reservedHeightDp = 16f).apply {
                addView(header)
                onCompactChanged = { header.compact = it }
            }
        private val headerBlock =
            verticalBlock(context).apply {
                content.headerPadding(this)
                addView(fitted, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        private val movies = CreditsRow(context, MOVIE_ROW, CardDimens.HEIGHT_2X3_DP.toFloat(), null)
        private val series = CreditsRow(context, SERIES_ROW, CardDimens.HEIGHT_2X3_DP.toFloat(), null)
        private val episodes = CreditsRow(context, EPISODE_ROW, CardDimens.HEIGHT_EPISODE_DP.toFloat(), AspectRatios.FOUR_THREE)
        private val discoverRow = detailCardRow(context)
        private val discoverBinder = DiscoverRowBinder(pageStyle) { _, item -> viewModel.navigationManager.navigateTo(item.destination) }

        fun firstFocus(): View? = header.firstFocus()

        fun bind(
            person: BaseItem,
            state: PersonState,
        ) {
            val name = person.name ?: person.id.toString()
            val imageUrl = CardImages.imageUrlService(requireContext()).getItemImageUrl(itemId = person.id, imageType = ImageType.PRIMARY)
            header.bind(
                name = name,
                overview = person.data.overview,
                imageUrl = imageUrl,
                birthdate = person.data.premiereDate?.toLocalDate(),
                deathdate = person.data.endDate?.toLocalDate(),
                birthPlace = person.data.productionLocations?.firstOrNull(),
                favorite = person.favorite,
                overviewOnClick = {
                    showOverviewDialog(
                        ItemDetailsDialogInfo(title = name, overview = person.data.overview, genres = listOf(), files = listOf()),
                        false,
                    )
                },
                favoriteOnClick = { viewModel.setFavorite(!person.favorite) },
            )
            // A long filmography is hard to navigate as a row, so it is cut at the same limit as the home page rows
            // and the rest is left to the full grid
            val maxItems = preferences.appPreferences.homePagePreferences.maxItemsPerRow
            val discovered = state.discovered.take(maxItems)
            val rows = mutableListOf(DetailRowSpec("header", { headerBlock }))
            rows += movies.spec("movies", getString(R.string.movies_title), state.movies)
            rows += series.spec("series", getString(R.string.tv_shows_title), state.series)
            rows += episodes.spec("episodes", getString(R.string.episodes), state.episodes)
            if (discovered.isNotEmpty()) {
                rows +=
                    DetailRowSpec("discover", { discoverRow }) {
                        val viewMore =
                            if (state.discovered.size > discovered.size) {
                                ViewMoreSpec(
                                    bind = { it.bind(pageStyle) },
                                    onClick = {
                                        state.discoverPerson?.let {
                                            viewModel.navigationManager.navigateTo(
                                                Destination.DiscoveredItem(it, maxItems),
                                            )
                                        }
                                    },
                                )
                            } else {
                                null
                            }
                        discoverBinder.bind(discoverRow, pageStyle, pageStyle.sectionAccent, discovered, viewMore)
                    }
            }
            content.list.listAdapter.submit(rows)
        }
    }

    /**
     * `LoadingRow` of a person's credits: "Loading…" / the error under the title while loading, the cards once loaded
     * (2:3 season cards, or episode-height ones for episodes), nothing when empty. Cards are read from the pager only
     * as they are shown.
     */
    private inner class CreditsRow(
        context: Context,
        private val priority: Int,
        private val imageHeightDp: Float,
        private val defaultAspect: Float?,
    ) {
        val view =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
                reportDetailRowHeight(priority)
            }
        private val placeholder = PlaceholderRowView(context)
        private val row = CardRowView(context)
        private val adapter =
            CardGridAdapter(
                cardPresenter<BaseItem, SeasonCardView>(::SeasonCardView) { card, item, _, _ ->
                    if (defaultAspect != null) {
                        card.bind(item, pageStyle, imageHeightDp = imageHeightDp, aspectRatio = item?.aspectRatio ?: defaultAspect)
                    } else {
                        card.bind(item, pageStyle, imageHeightDp = imageHeightDp)
                    }
                },
                CardCallbacks(onClick = {
                    _,
                    item,
                    ->
                    viewModel.navigationManager.navigateTo(item.destination())
                }, onLongClick = { _, _ -> }),
            )

        init {
            view.addView(placeholder, FrameLayout.LayoutParams(MATCH, WRAP))
            view.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        fun spec(
            key: String,
            prefix: String,
            state: RowLoadingState,
        ): DetailRowSpec =
            DetailRowSpec(key, { view }) {
                bind(rowTitle(prefix, state), state)
                if (state is RowLoadingState.Success) watchPager(key, state.items) { bind(rowTitle(prefix, state), state) }
            }

        private fun bind(
            title: String,
            state: RowLoadingState,
        ) {
            val ctx = view.context
            val accent = pageStyle.sectionAccent
            when (state) {
                is RowLoadingState.Error -> {
                    showPlaceholder(title, state.localizedMessage, true)
                }

                RowLoadingState.Pending, RowLoadingState.Loading -> {
                    showPlaceholder(title, ctx.getString(R.string.loading), false)
                }

                is RowLoadingState.Success -> {
                    if (state.items.isNotEmpty()) {
                        placeholder.visibility = View.GONE
                        row.visibility = View.VISIBLE
                        view.visibility = View.VISIBLE
                        val spacing =
                            if (BuildConfig.FLAVOR == "weaselfin") {
                                ApprovedHomeLayout.CARD_SPACING_DP.toFloat()
                            } else if (priority == EPISODE_ROW) {
                                24f
                            } else {
                                16f
                            }
                        row.showPaged(title, accent, pageStyle, detailItemRowStyle(ctx).copy(spacingDp = spacing), adapter, state.items)
                    } else {
                        // showIfEmpty = false
                        view.visibility = View.GONE
                    }
                }
            }
        }

        private fun showPlaceholder(
            title: String,
            message: String,
            error: Boolean,
        ) {
            view.visibility = View.VISIBLE
            row.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
            val ctx = view.context
            placeholder.bind(
                title,
                message,
                pageStyle.sectionAccent,
                isError = error,
                focusableRow = false,
                insetStartPx = ctx.neonGutterStartPx(),
                insetEndPx = ctx.neonGutterEndPx(),
            )
        }
    }
}

/**
 * `PersonHeader`: the picture (240dp tall, 160dp in a compact header; 10 % rounded corners) beside a panel three times
 * as wide (surface at 1dp elevation, 10 % corners, 16dp padding, 32dp from the end) with the name (displaySmall,
 * headlineSmall compact; two lines), born / birthplace / died (titleSmall, one line each), the overview (3 lines) and
 * the Watchlist button; 32dp apart.
 */
class PersonHeaderView(
    context: Context,
) : LinearLayout(context) {
    private val image =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = PercentRoundOutline(.10f)
        }
    private val panel =
        FractionColumn(context, 1f, 8f).apply {
            background = PercentRoundDrawable(AppTheme.palette.surfaceAtElevation(1f), .10f)
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        }
    private val name = detailText(context, scaledRole(TextRole.DISPLAY_SMALL, 1f), maxLines = 2)
    private val born = line(context)
    private val birthPlace = line(context)
    private val died = line(context)
    private val overview = OverviewTextView(context).apply { maxLines = 3 }
    private val favoriteButton = DetailButtons.fa(context, R.string.add_favorite, R.string.fa_heart) { onFavorite() }
    private var onFavorite: () -> Unit = {}

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, context.dp(16), 0, context.dp(16))
        addView(image, LayoutParams(0, context.dp(240), 1f))
        panel.addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(8) })
        panel.addView(born, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(birthPlace, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(died, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        panel.addView(favoriteButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            panel,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f).apply {
                marginStart = context.dp(32)
                topMargin = context.dp(16)
                marginEnd = context.dp(32)
            },
        )
    }

    var compact: Boolean = false
        set(value) {
            field = value
            (image.layoutParams as LayoutParams).height = context.dp(if (value) 160 else 240)
            image.requestLayout()
            name.applyTextStyle(scaledRole(if (value) TextRole.HEADLINE_SMALL else TextRole.DISPLAY_SMALL, 1f))
        }

    /** The overview on other themes (it is focusable there), else the Watchlist button */
    fun firstFocus(): View = if (overview.isFocusable) overview else favoriteButton

    fun bind(
        name: String,
        overview: String?,
        imageUrl: String?,
        birthdate: LocalDate?,
        deathdate: LocalDate?,
        birthPlace: String?,
        favorite: Boolean,
        overviewOnClick: () -> Unit,
        favoriteOnClick: () -> Unit,
    ) {
        val res = context.resources
        this.name.text = name
        image.contentDescription = name
        image.loadImageUrl(imageUrl)
        val bornLabel = context.getString(R.string.born)
        if (birthdate != null) {
            val age = if (deathdate == null) birthdate.until(LocalDate.now())?.years else null
            born.text =
                if (age != null) {
                    "$bornLabel: ${formatDate(birthdate)} (${res.getQuantityString(R.plurals.years_old, age, age)})"
                } else {
                    "$bornLabel: ${formatDate(birthdate)}"
                }
            born.visibility = View.VISIBLE
        } else {
            born.visibility = View.GONE
        }
        if (birthPlace.isNotNullOrBlank()) {
            this.birthPlace.text = context.getString(R.string.birthplace) + ": $birthPlace"
            this.birthPlace.visibility = View.VISIBLE
        } else {
            this.birthPlace.visibility = View.GONE
        }
        if (deathdate != null) {
            val age = birthdate?.until(deathdate)?.years
            val diedLabel = context.getString(R.string.died)
            died.text =
                if (age != null) {
                    "$diedLabel: ${formatDate(deathdate)} (${res.getQuantityString(R.plurals.years_old, age, age)})"
                } else {
                    "$diedLabel: ${formatDate(deathdate)}"
                }
            died.visibility = View.VISIBLE
        } else {
            died.visibility = View.GONE
        }
        this.overview.setText(overview ?: "")
        this.overview.onClick = overviewOnClick
        onFavorite = favoriteOnClick
        DetailButtons.updateFa(
            favoriteButton,
            context.getString(if (favorite) R.string.remove_favorite else R.string.add_favorite),
            R.string.fa_heart,
            if (favorite) FAVORITE_RED else null,
        )
    }

    private fun line(context: Context): TextView = detailText(context, scaledRole(TextRole.TITLE_SMALL, 1f), maxLines = 1)
}

/** `RoundedCornerShape(percent)`: corners [fraction] of the smaller side, as an outline */
internal class PercentRoundOutline(
    private val fraction: Float,
) : ViewOutlineProvider() {
    override fun getOutline(
        view: View,
        outline: Outline,
    ) {
        outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) * fraction)
    }
}

/** A filled rectangle with corners [fraction] of its smaller side (`background(color, RoundedCornerShape(percent))`) */
internal class PercentRoundDrawable(
    color: Int,
    private val fraction: Float,
) : GradientDrawable() {
    init {
        setColor(color)
    }

    override fun onBoundsChange(r: android.graphics.Rect) {
        super.onBoundsChange(r)
        cornerRadius = min(r.width(), r.height()) * fraction
    }
}
