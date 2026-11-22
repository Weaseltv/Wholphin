package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import coil3.load
import coil3.svg.SvgDecoder
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlBorder
import com.github.damontecres.wholphin.leanback.theme.ControlColors
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceStyle
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyNeonEyebrow
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.provideNeonAccent
import com.github.damontecres.wholphin.leanback.theme.streamingProviderAccent
import com.github.damontecres.wholphin.leanback.theme.typeAccent
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.text.GenreTextView
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.leanback.widget.text.logoUrlFor
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailActionHeight
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.services.StreamingQuickList
import com.github.damontecres.wholphin.ui.components.StreamingTypeFilters
import com.github.damontecres.wholphin.ui.components.TypeFilterOption
import com.github.damontecres.wholphin.ui.components.itemKindLabel
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.detail.collection.CollectionState
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale

/*
 * The collection page's pieces as Views: CollectionDetailsHeader.kt, CollectionButtons.kt (with TypeFilterButton and
 * QuickListButton from TypeFilterButton.kt), StreamingProviderWordmark / StreamingProviderHeader and the focused
 * item's header (HomePage.kt `HomePageHeader`; DETAILS-private ports of LIBRARY's and HOME's composables).
 */

/** `collectionGridTopPadding()`: room above the grid's first row for a focused card's enlargement and glow */
internal fun collectionGridTopPaddingDp(): Float = if (AppTheme.isNeon) 20f else 16f

/** The bundled wordmark file of a streaming service (`providerWordmark`), or null */
internal fun providerWordmark(name: String?): String? =
    when (name?.trim()?.lowercase(Locale.ROOT)) {
        "netflix" -> "netflix.svg"
        "disney+", "disney plus" -> "disney.svg"
        "hulu" -> "hulu.svg"
        "max", "hbo", "hbo max", "max (hbo)" -> "max.svg"
        "prime video", "amazon prime video" -> "prime_video.svg"
        "paramount+", "paramount plus" -> "paramount.svg"
        "peacock" -> "peacock.svg"
        "apple tv", "apple tv+", "apple tv plus" -> "apple_tv.svg"
        "amc+", "amc plus" -> "amc.png"
        "mgm+", "mgm plus" -> "mgm.svg"
        "starz" -> "starz.svg"
        "britbox", "brit box" -> "britbox.svg"
        "crunchyroll" -> "crunchyroll.svg"
        "hallmark", "hallmark+", "hallmark plus" -> "hallmark.svg"
        "angel", "angel studios" -> "angel.svg"
        else -> null
    }

/**
 * `StreamingProviderWordmark`: the service's bundled wordmark, fitted, tinted like its Home row (its own colour for
 * some, white for the rest, untouched for Peacock). Hidden for a service without one.
 */
class StreamingWordmarkView(
    context: Context,
) : ImageView(context) {
    init {
        scaleType = ScaleType.FIT_CENTER
    }

    fun bind(
        name: String?,
        fallbackAccent: Int,
    ) {
        val wordmark = providerWordmark(name)
        if (wordmark == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        val providerColor = streamingProviderAccent(name) ?: fallbackAccent
        colorFilter =
            when (wordmark) {
                "peacock.svg" -> {
                    null
                }

                "netflix.svg", "hulu.svg", "prime_video.svg", "amc.png", "mgm.svg",
                "britbox.svg", "crunchyroll.svg", "hallmark.svg",
                -> {
                    PorterDuffColorFilter(providerColor, PorterDuff.Mode.SRC_IN)
                }

                else -> {
                    PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                }
            }
        load("file:///android_asset/provider-wordmarks/$wordmark") {
            if (wordmark.endsWith(".svg")) decoderFactory(SvgDecoder.Factory())
        }
    }
}

/**
 * `CollectionDetailsHeader`: the title or logo (75 % wide; 46 % beside a streaming service's wordmark on the WeaselTV
 * theme, which sits at the end, half the width, 110dp tall, 96dp in), then the quick details (no age rating for
 * streaming collections), genres, tagline and overview (3 lines; 2 for streaming collections, without "in WeaselPlex").
 */
class CollectionHeaderView(
    context: Context,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private val wordmark = StreamingWordmarkView(context)
    private val wordmarkBox = FractionColumn(context, .5f)
    private val column = FractionColumn(context, 1f, 4f)
    private val titleColumn = FractionColumn(context, .75f)
    private val title = TitleOrLogoView(context)
    private val info = FractionColumn(context, .60f, 4f)
    private val quickDetails = QuickDetailsView(context)
    private val genres = GenreTextView(context)
    private val tagline = taglineView(context)
    private val overview = OverviewTextView(context)

    init {
        clipChildren = false
        wordmarkBox.addView(wordmark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(110)))
        addView(
            wordmarkBox,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END),
        )
        titleColumn.addPadded(title)
        column.addView(titleColumn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        info.addPadded(quickDetails)
        info.addPadded(genres)
        info.addPadded(tagline)
        info.addView(overview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    var compact: Boolean = false
        set(value) {
            field = value
            title.compactHeader = value
            overview.compact = value
            genres.compact = value
        }

    fun bind(
        collection: BaseItem,
        streaming: Boolean,
        logoImageUrl: String?,
        preferences: UserPreferences,
        accent: Int,
        overviewOnClick: () -> Unit,
    ) {
        val dto = collection.data
        val showWordmark = streaming && neon
        if (showWordmark) {
            wordmarkBox.visibility = View.VISIBLE
            wordmark.bind(collection.name, accent)
            // 96dp from the end (the box is half the width, its content inset)
            wordmarkBox.setPaddingRelative(0, 0, context.dp(96), 0)
        } else {
            wordmarkBox.visibility = View.GONE
        }
        titleColumn.fraction = if (showWordmark) .46f else .75f
        info.fraction = if (showWordmark) .46f else .60f
        title.bind(collection.name, logoImageUrl, preferences.appPreferences.interfacePreferences.showLogos)
        val quick = if (streaming) collection.ui.quickDetailsText.copy(officialRating = null) else collection.ui.quickDetailsText
        quickDetails.bind(quick, collection.timeRemainingOrRuntime, enabledToggles = enabledDisplayToggles(preferences.appPreferences))
        val genreList = dto.genres.orEmpty()
        genres.visibility = if (genreList.isNotEmpty()) View.VISIBLE else View.GONE
        genres.setGenres(genreList)
        val taglineText = dto.taglines?.firstOrNull()
        tagline.visibility = if (taglineText != null) View.VISIBLE else View.GONE
        tagline.text = taglineText
        val text = dto.overview
        overview.visibility = if (text != null) View.VISIBLE else View.GONE
        overview.maxLines = if (streaming) 2 else 3
        overview.setText(if (streaming) text?.replace(" in WeaselPlex", "", ignoreCase = true) else text)
        overview.onClick = overviewOnClick
        requestLayout()
    }
}

/**
 * The focused item's header over a collection's grid (`HomePageHeader`): on the WeaselTV theme the "KIND · GENRES"
 * eyebrow in the item's accent, the title or logo (75 % wide), then (92 % wide on the WeaselTV theme, else 60 %) the
 * episode name, quick details and overview (Neon body type in white, 2 lines for episodes, else 3; 400dp wide on other
 * themes; space kept when there is none). A streaming service's collection shows its provider header instead.
 */
class FocusedItemHeaderView(
    context: Context,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private val standard = FractionColumn(context, 1f, 4f)
    private val eyebrow = AppCompatTextView(context)
    private val titleColumn = FractionColumn(context, .75f)
    private val title = TitleOrLogoView(context)
    private val info = FractionColumn(context, if (neon) .92f else .6f, 8f)
    private val episodeName = episodeNameView(context)
    private val quickDetails = QuickDetailsView(context)
    private val overview =
        AppCompatTextView(context).apply {
            applyTextStyle(if (neon) NeonType.body() else com.github.damontecres.wholphin.leanback.theme.ControlTextStyle.of(TextRole.BODY_MEDIUM))
            setTextColor(if (neon) 0xFFFFFFFF.toInt() else AppTheme.palette.onSurface)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    private val overviewSpace = View(context)
    private val provider = StreamingProviderHeaderView(context)

    init {
        clipChildren = false
        standard.addView(eyebrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(4) })
        titleColumn.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        standard.addView(titleColumn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        info.addView(episodeName, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        info.addView(quickDetails, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        info.addView(
            overview,
            LinearLayout.LayoutParams(if (neon) LinearLayout.LayoutParams.MATCH_PARENT else context.dp(400), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        info.addView(overviewSpace, LinearLayout.LayoutParams(if (neon) LinearLayout.LayoutParams.MATCH_PARENT else context.dp(400), 0))
        standard.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(standard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(provider, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    var compact: Boolean = false
        set(value) {
            field = value
            title.compactHeader = value
        }

    fun bind(
        item: BaseItem?,
        preferences: UserPreferences,
        pageStyle: PageStyle,
    ) {
        if (neon && item != null && StreamingCollections.isStreamingCollection(item.data)) {
            standard.visibility = View.GONE
            provider.visibility = View.VISIBLE
            provider.bind(item, preferences, pageStyle.sectionAccent)
            return
        }
        standard.visibility = View.VISIBLE
        provider.visibility = View.GONE
        val isEpisode = item?.type == BaseItemKind.EPISODE
        val dto = item?.data
        if (neon && item != null) {
            eyebrow.visibility = View.VISIBLE
            val text =
                listOfNotNull(
                    itemKindLabel(context, item.type),
                    dto?.genres?.take(2)?.joinToString(" · ")?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            eyebrow.applyNeonEyebrow(text, pageStyle.accentFor(item))
        } else {
            eyebrow.visibility = View.GONE
        }
        title.bind(item?.title, logoUrlFor(item, CardImages.imageUrlService(context)), preferences.appPreferences.interfacePreferences.showLogos)
        episodeName.visibility = if (isEpisode) View.VISIBLE else View.GONE
        episodeName.text = if (isEpisode) dto?.name else null
        quickDetails.bind(item?.ui?.quickDetailsText, item?.timeRemainingOrRuntime, endsAt = item?.data?.endDate, enabledToggles = enabledDisplayToggles(preferences.appPreferences))
        val text = dto?.overview
        if (text.isNotNullOrBlank()) {
            overview.visibility = View.VISIBLE
            overviewSpace.visibility = View.GONE
            overview.text = text
            overview.maxLines = if (isEpisode) 2 else 3
        } else {
            overview.visibility = View.GONE
            overviewSpace.visibility = View.VISIBLE
            overviewSpace.layoutParams = overviewSpace.layoutParams.apply { height = context.dp(48 + if (!isEpisode) 12 else 0) }
        }
    }
}

/**
 * `StreamingProviderHeader`: the service's wordmark at the end (56 % wide, 64dp in, 120dp tall) beside the "Collection"
 * eyebrow, the title, quick details and "Everything on …" (two lines), 42 % wide.
 */
class StreamingProviderHeaderView(
    context: Context,
) : FrameLayout(context) {
    private val wordmark = StreamingWordmarkView(context)
    private val wordmarkBox = FractionColumn(context, .56f)
    private val column = FractionColumn(context, .42f, 8f)
    private val eyebrow = AppCompatTextView(context)
    private val title = TitleOrLogoView(context)
    private val quickDetails = QuickDetailsView(context)
    private val description =
        AppCompatTextView(context).apply {
            applyTextStyle(NeonType.body())
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    init {
        wordmarkBox.setPaddingRelative(0, 0, context.dp(64), 0)
        wordmarkBox.addView(wordmark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(120)))
        addView(wordmarkBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        column.addView(eyebrow)
        column.addView(title)
        column.addView(quickDetails)
        column.addView(description)
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        item: BaseItem,
        preferences: UserPreferences,
        accent: Int,
    ) {
        eyebrow.applyNeonEyebrow(context.getString(R.string.collection), NeonSections.Collections.glow)
        wordmark.bind(item.name, accent)
        title.bind(item.title, null, false)
        quickDetails.bind(item.ui.quickDetailsText, item.timeRemainingOrRuntime, endsAt = item.data.endDate, enabledToggles = enabledDisplayToggles(preferences.appPreferences))
        description.text = context.getString(R.string.streaming_provider_description, item.name.orEmpty())
    }
}

/**
 * `TypeFilterButton` (ALL / MOVIES / SHOWS): an outline in the type's colour (rest and focus), its label always in that
 * colour, uppercase, 12dp side padding, 40dp tall.
 */
internal fun typeFilterButton(
    context: Context,
    option: TypeFilterOption,
    onClick: () -> Unit,
): AppButton =
    AppButton(context).apply {
        val accent = typeAccent(option.accentKind)
        setSurfaceStyle { scope ->
            val base = ControlStyles.button(scope.copy(accent = accent))
            if (AppTheme.isNeon) base.copy(border = ControlStyles.surfaceBorder(accent, 1f, accent)) else base
        }
        setContentPaddingDp(12f, 0f, 12f, 0f)
        contentHeightDp = MIN_BUTTON_DP
        setLabel(context.getString(option.title), allCaps = true)
        onContentColor = { labelView.setTextColor(accent) }
        setOnClickListener { onClick() }
    }

/**
 * `QuickListButton` (Popular / Trending / Top 10 / New on a streaming collection): the collection's accent; filled
 * (`chipOn`) and outlined 2dp in the accent while its list shows, else a 1dp `line2` outline; white label, the accent
 * when focused; a 14dp spinner before the label while its list loads.
 */
internal class QuickListButtonHolder(
    context: Context,
    private val quickList: StreamingQuickList,
    onClick: () -> Unit,
) {
    private val spinner = CircularProgressView(context)
    private var selected = false

    val button: AppButton =
        AppButton(context).apply {
            setContentPaddingDp(12f, 0f, 12f, 0f)
            contentHeightDp = MIN_BUTTON_DP
            setLabel(context.getString(quickList.title), allCaps = true)
            addView(spinner, 0, LinearLayout.LayoutParams(context.dp(14), context.dp(14)).apply { marginEnd = context.dp(6) })
            spinner.visibility = View.GONE
            setOnClickListener { onClick() }
        }

    fun bind(
        selected: Boolean,
        loading: Boolean,
    ) {
        this.selected = selected
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        button.setSurfaceStyle { scope ->
            val accent = scope.accentOrDefault
            val chipOn = NeonColors.chipOn(accent)
            val restColor = if (selected) accent else scope.outlineRest ?: NeonColors.LINE2
            ControlSurfaceStyle(
                container = ControlColors(rest = if (selected) chipOn else 0, focused = chipOn, pressed = chipOn),
                content = ControlColors(rest = NeonColors.TEXT, focused = accent, pressed = accent),
                border =
                    if (AppTheme.isNeon) {
                        ControlStyles.surfaceBorder(accent, if (selected) 2f else 1f, restColor)
                    } else {
                        com.github.damontecres.wholphin.leanback.theme.ControlBorders.NONE
                    },
                glow = ControlStyles.surfaceGlow(accent),
                scale = com.github.damontecres.wholphin.leanback.theme.ControlScales.BUTTON,
                cornerDp = if (AppTheme.isNeon) 0f else ControlSurfaceStyle.CORNER_PILL,
            )
        }
    }
}

/**
 * `CollectionButtons`: ALL / MOVIES / SHOWS on the first row (8dp above, 16dp below); on the second (16dp from the end,
 * 8dp below) the collection's actions on the left (Watchlist, Delete, View options, More; the quick lists on a
 * streaming collection) and Random, sort and filter as expanding icons on the right. Streaming collections use the
 * service's colour for focus. Reported as the detail action bar.
 *
 * Focus starts on ALL and returns to the last focused button when the buttons come back.
 */
internal class CollectionButtonsView(
    private val fragment: androidx.fragment.app.Fragment,
    context: Context,
    private val actions: Actions,
) : RestoringFocusColumn(context) {
    class Actions(
        val onFilterChange: (GetItemsFilter) -> Unit,
        val onSortChange: (SortAndDirection) -> Unit,
        val onClickRandom: () -> Unit,
        val onQuickListChange: (StreamingQuickList) -> Unit,
        val getPossibleFilterValues: suspend (com.github.damontecres.wholphin.data.filter.ItemFilterBy<*>) -> List<com.github.damontecres.wholphin.data.filter.FilterValueOption>,
        val onClickViewOptions: () -> Unit,
        val favoriteOnClick: () -> Unit,
        val onConfirmDelete: () -> Unit,
        val moreOnClick: () -> Unit,
    )

    private var state: CollectionState? = null
    private val typeRow = buttonRow(context)
    private val actionRow = buttonRow(context)
    private val leftGroup = buttonRow(context)
    private val rightGroup = buttonRow(context)
    private val typeButtons =
        StreamingTypeFilters.map { option ->
            option to
                typeFilterButton(context, option) {
                    state?.let { actions.onFilterChange(it.itemFilter.copy(includeItemTypes = option.types)) }
                }
        }
    private val quickLists = StreamingQuickList.entries.map { q -> QuickListButtonHolder(context, q) { actions.onQuickListChange(q) } }
    private val favoriteButton = DetailButtons.fa(context, R.string.add_favorite, R.string.fa_heart, allCaps = true) { actions.favoriteOnClick() }
    private val deleteButton = DetailButtons.delete(context, title = { state?.collection?.title ?: "" }, allCaps = true) { actions.onConfirmDelete() }
    private val viewOptionsButton = DetailButtons.fa(context, R.string.view_options, R.string.fa_sliders, allCaps = true) { actions.onClickViewOptions() }
    private val moreButton = DetailButtons.fa(context, R.string.more, R.string.fa_ellipsis_vertical, allCaps = true) { actions.moreOnClick() }
    private val randomButton = DetailButtons.fa(context, R.string.sort_by_random, R.string.fa_dice, collapsed = true) { actions.onClickRandom() }
    private val sortButton = SortButton(context, MovieSortOptions, collapsed = true) { actions.onSortChange(it) }
    private val filterButton =
        FilterButton(fragment, context, DefaultFilterOptions, actions.getPossibleFilterValues, allCaps = true) { actions.onFilterChange(it) }

    init {
        typeButtons.forEach { (_, b) -> typeRow.addView(b) }
        typeRow.setPadding(0, context.dp(8), 0, context.dp(16))
        addView(typeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        quickLists.forEach { leftGroup.addView(it.button) }
        listOf(favoriteButton, deleteButton, viewOptionsButton, moreButton).forEach(leftGroup::addView)
        listOf(randomButton, sortButton.button, filterButton.button).forEach(rightGroup::addView)
        actionRow.addView(leftGroup, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(rightGroup, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        actionRow.setPaddingRelative(0, 0, context.dp(16), context.dp(8))
        addView(actionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        firstFocus = typeButtons.first().second
        reportDetailActionHeight()
    }

    private fun buttonRow(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            showDividers = SHOW_DIVIDER_MIDDLE
            dividerDrawable = SpaceDrawable(context.dp(16))
        }

    fun bind(
        state: CollectionState,
        canDelete: Boolean,
        sectionAccent: Int,
    ) {
        this.state = state
        // Streaming collections use the service's colour for focus borders and glow
        val serviceColor = if (state.isStreaming) streamingProviderAccent(state.collection?.name) else null
        provideNeonAccent(serviceColor ?: sectionAccent)
        val ranked = state.justWatch != null
        quickLists.forEach { holder ->
            // Only services the server keeps JustWatch lists for; any others stay on their newest-first grid
            val show = state.isStreaming && ranked
            holder.button.visibility = if (show) View.VISIBLE else View.GONE
            holder.bind(selected = state.quickList == holderList(holder), loading = state.quickListLoading == holderList(holder))
        }
        val local = !state.isStreaming
        val favorite = state.collection?.favorite == true
        DetailButtons.updateFa(
            favoriteButton,
            context.getString(if (favorite) R.string.remove_favorite else R.string.add_favorite),
            R.string.fa_heart,
            if (favorite) FAVORITE_RED else null,
        )
        favoriteButton.visibility = if (local) View.VISIBLE else View.GONE
        if (!(local && canDelete) && deleteButton.hasFocus()) favoriteButton.requestFocus()
        deleteButton.visibility = if (local && canDelete) View.VISIBLE else View.GONE
        viewOptionsButton.visibility = if (local) View.VISIBLE else View.GONE
        moreButton.visibility = if (local) View.VISIBLE else View.GONE
        sortButton.bind(state.sortAndDirection)
        filterButton.bind(state.itemFilter)
    }

    private fun holderList(holder: QuickListButtonHolder): StreamingQuickList = StreamingQuickList.entries[quickLists.indexOf(holder)]
}

/**
 * A vertical group of buttons that remembers its last focused button (Compose `focusGroup().focusRestorer(firstFocus)`):
 * entering it from outside lands there, else on [firstFocus]. Inside, the D-pad moves between buttons as usual.
 */
internal open class RestoringFocusColumn(
    context: Context,
) : LinearLayout(context) {
    var firstFocus: View? = null
    private var lastFocused: View? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    val restoreTarget: View?
        get() = lastFocused?.takeIf { it.isShown && it.isFocusable && isInside(it) } ?: firstFocus?.takeIf { it.isShown }

    private fun isInside(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    override fun requestChildFocus(
        child: View?,
        focused: View?,
    ) {
        super.requestChildFocus(child, focused)
        if (focused != null) lastFocused = focused
    }

    override fun addFocusables(
        views: ArrayList<View>,
        direction: Int,
        focusableMode: Int,
    ) {
        if (!hasFocus()) {
            val target = restoreTarget
            if (target != null) {
                views.add(target)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ): Boolean {
        restoreTarget?.let { if (it.requestFocus(direction, previouslyFocusedRect)) return true }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}

/** Placeholder text view helper for collection pieces */
internal fun plainText(
    context: Context,
    role: TextRole,
): TextView =
    AppCompatTextView(context).apply {
        applyTextStyle(com.github.damontecres.wholphin.leanback.theme.ControlTextStyle.of(role))
        setTextColor(AppTheme.palette.onSurface)
    }

/** The height of a header box on other themes (`HeaderUtils.height`, plus its paddings) */
internal fun ViewGroup.fixHeaderHeight(
    heightDp: Float?,
    topDp: Float,
    bottomDp: Float,
) {
    minimumHeight = 0
    if (heightDp != null) {
        layoutParams = (layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)).apply {
            height = context.dp(heightDp + topDp + bottomDp)
        }
    }
}
