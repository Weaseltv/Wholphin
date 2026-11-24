package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.viewModels
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.GridLayoutManager
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultPlaylistItemsOptions
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonGlowSpec
import com.github.damontecres.wholphin.leanback.theme.NeonSizes
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyNeonEyebrow
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.card.ItemImageState
import com.github.damontecres.wholphin.leanback.widget.card.ItemImageView
import com.github.damontecres.wholphin.leanback.widget.card.clipToOwnHeight
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.text.AppClock
import com.github.damontecres.wholphin.leanback.widget.text.FittedTitleView
import com.github.damontecres.wholphin.leanback.widget.text.NeonRuleView
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.leanback.widget.text.TimeDisplayView
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.data.BoxSetSortOptions
import com.github.damontecres.wholphin.ui.data.getStringRes
import com.github.damontecres.wholphin.ui.detail.PlaylistDetailsState
import com.github.damontecres.wholphin.ui.detail.PlaylistViewModel
import com.github.damontecres.wholphin.ui.formatDateTime
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks

/**
 * The playlist page (PlaylistDetails.kt): the page title, then on the left (a quarter of the width, or wider when its
 * buttons need it; level with the first entry) Play and Shuffle, the filter and sort buttons and the focused entry's
 * title, subtitle, date (episodes) and overview (as many lines as fit); on the
 * right the entries in a panel: number, picture, title and subtitle (scrolling while focused), runtime and end time,
 * then the move up / down buttons (when the list is in its own order, unfiltered and editable) and More.
 *
 * Focus starts on the list (Play when it is empty) and comes back to where it was; leaving the list in any direction
 * goes to Play. OK on an entry plays the playlist from it; holding OK or More opens its menu (with Remove from playlist
 * when editable). Audio playlists are not supported (a message says so). The focused entry is the backdrop.
 */
@AndroidEntryPoint
class PlaylistDetailsFragment : DetailPageFragment() {
    private val viewModel: PlaylistViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<PlaylistViewModel.Factory> {
                it.create(requireDestination<Destination.MediaItem>().itemId)
            }
        },
    )

    private var page: PlaylistPage? = null
    private var savedIndex = 0

    private val contextActions by lazy {
        ContextMenuActions(
            navigateTo = { navigationManager.navigateTo(it) },
            onClickWatch = { id, watched -> viewModel.setWatched(id, watched) },
            onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite) },
            onClickAddPlaylist = { showPlaylistDialog(it) },
            onSendMediaInfo = viewModel::sendMediaReport,
            onDeleteItem = viewModel::deleteItem,
            onShowOverview = {},
            onChooseVersion = { _, _ -> },
            onChooseTracks = {},
            onClearChosenStreams = {},
            onClickRemoveFromNextUp = {},
            onRemoveFromPlaylist = viewModel::removeFromPlaylist,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        // The page shows its own loading and error states inside the list panel
        page = PlaylistPage(requireContext())
        showContent { page!!.root }
        return view
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.state.collectWhileStarted { page?.bind(it) }
    }

    override fun onDestroyView() {
        page?.release()
        page = null
        super.onDestroyView()
    }

    override fun onPageStyleChanged(style: PageStyle) {
        page?.bind(viewModel.state.value)
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring) page?.firstFocus()?.let { if (it.isAttachedToWindow && it.requestFocus()) return }
        super.onScreenFocus(restoring)
    }

    private fun play(
        index: Int,
        item: BaseItem,
        shuffle: Boolean,
    ) {
        val state = viewModel.state.value
        when (state.mediaType) {
            MediaType.AUDIO -> {
                viewModel.audioNotSupported()
            }

            else -> {
                navigationManager.navigateTo(
                    Destination.PlaybackList(
                        itemId = requireDestination<Destination.MediaItem>().itemId,
                        startIndex = index,
                        startItemId = item.id.takeUnless { shuffle },
                        shuffle = shuffle,
                        filter = state.filterAndSort.filter,
                        sortAndDirection = state.filterAndSort.sortAndDirection,
                    ),
                )
            }
        }
    }

    private fun showMenu(
        index: Int,
        item: BaseItem,
        fromLongClick: Boolean,
        opener: View?,
    ) {
        val state = viewModel.state.value
        showContextMenu(
            ContextMenu.ForBaseItem(
                fromLongClick = fromLongClick,
                item = item,
                index = index,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = viewModel.canDelete(item, preferences.appPreferences),
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = contextActions,
                showRemoveFromPlaylist = state.canEdit,
            ),
            kidPage = false,
            getMediaSource = null,
            preferredSubtitleLanguage = null,
            opener = opener,
        )
    }

    private inner class PlaylistPage(
        context: Context,
    ) {
        val root =
            FractionColumn(context, 1f, 16f).apply {
                clipChildren = false
                clipToPadding = false
            }
        private val title = GridTitleView(context)
        private var focusedIndex = savedIndex
        private var loading: LoadingState = LoadingState.Pending
        private var items: List<BaseItem?> = emptyList()
        private var canMove = false

        // Left: the header
        private val playButton =
            DetailButtons.play(context, R.string.play, R.drawable.lb_widget_ctl_ic_play_arrow) {
                viewModel.state.value.playlist
                    ?.let { play(0, it, false) }
            }
        private val shuffleButton =
            DetailButtons.fa(context, R.string.shuffle, R.string.fa_shuffle) {
                viewModel.state.value.playlist
                    ?.let { play(0, it, true) }
            }
        private val filterButton =
            FilterButton(this@PlaylistDetailsFragment, context, DefaultPlaylistItemsOptions, viewModel::getFilterOptionValues) { filter ->
                viewModel.loadItems(filter, viewModel.state.value.filterAndSort.sortAndDirection)
            }
        private val sortButton =
            SortButton(
                context,
                BoxSetSortOptions,
                collapsed = false,
                // The playlist's own order is "Playlist order", not "Default"
                labelFor = { if (it == ItemSortBy.DEFAULT) R.string.playlist_order else getStringRes(it) },
            ) { sort ->
                viewModel.loadItems(viewModel.state.value.filterAndSort.filter, sort)
            }
        private val focusedTitle =
            detailText(
                context,
                com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
                    .of(TextRole.HEADLINE_SMALL),
            )
        private val focusedSubtitle =
            detailText(
                context,
                com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
                    .of(TextRole.TITLE_MEDIUM),
            )
        private val focusedDate =
            detailText(
                context,
                com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
                    .of(TextRole.TITLE_SMALL),
            )
        private val focusedOverview =
            OverviewTextView(context).apply {
                maxLines = 10
                // `enabled = false`: never focusable
                isFocusable = false
                isClickable = false
                // Starts on the same edge as the title and buttons (no side inset)
                textView.setPadding(0, textView.paddingTop, 0, textView.paddingBottom)
                // As many whole lines as fit above the bottom margin, then "…"
                addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
                    val space = bottom - top - textView.paddingTop - textView.paddingBottom
                    val lines = (space / textView.lineHeight.coerceAtLeast(1)).coerceAtLeast(1)
                    if (lines != maxLines) v.post { maxLines = lines }
                }
            }

        // Right: the entries
        private val listPanel =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                background =
                    GradientDrawable().apply {
                        setColor(withAlpha(AppTheme.palette.surfaceAtElevation(1f), .75f))
                        cornerRadius = if (AppTheme.isNeon) 0f else context.dpF(16f)
                    }
            }
        private val list = PlaylistList(context)
        private val adapter = PlaylistAdapter()
        private val loadingView = LoadingPageView(context, focusEnabled = false)
        private var errorView: View? = null
        private val emptyView =
            AppCompatTextView(context).apply {
                setText(R.string.no_results)
                applyTextRole(TextRole.TITLE_LARGE)
                setTextColor(AppTheme.palette.onSurface)
                gravity = Gravity.CENTER
            }
        private val clockScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var clockJob: Job? = null

        init {
            root.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
            val buttons =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                    dividerDrawable = SpaceDrawable(context.dp(8))
                    addView(playButton)
                    addView(shuffleButton)
                }
            val filters =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                    dividerDrawable = SpaceDrawable(context.dp(8))
                    addView(filterButton.button)
                    addView(sortButton.button)
                }
            // Button labels stay on one line ("SHUFFLE" split into "SHUFF / LE" in a quarter-width column)
            listOf(playButton, shuffleButton).forEach { it.labelView.isSingleLine = true }
            val header =
                object : FractionColumn(context, 1f, 8f) {
                    // A quarter of the row, or wider when the buttons need it
                    override fun onMeasure(
                        widthMeasureSpec: Int,
                        heightMeasureSpec: Int,
                    ) {
                        val available = MeasureSpec.getSize(widthMeasureSpec)
                        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        buttons.measure(unspecified, unspecified)
                        filters.measure(unspecified, unspecified)
                        val needed = maxOf(buttons.measuredWidth, filters.measuredWidth) + paddingLeft + paddingRight
                        val w = maxOf(available / 4, needed).coerceAtMost(available)
                        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
                    }
                }.apply {
                    // Play starts level with the first entry (the list's top padding), not 80dp lower
                    setPadding(0, context.dp(MenuDialog.DIALOG_LIST_EDGE_DP), 0, 0)
                    addView(buttons, LinearLayout.LayoutParams(WRAP, WRAP))
                    addView(filters, LinearLayout.LayoutParams(WRAP, WRAP))
                    addView(focusedTitle, LinearLayout.LayoutParams(MATCH, WRAP))
                    addView(focusedSubtitle, LinearLayout.LayoutParams(MATCH, WRAP))
                    addView(focusedDate, LinearLayout.LayoutParams(MATCH, WRAP))
                    // The overview takes the space left, ending at the list panel's bottom margin
                    addView(focusedOverview, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = context.dp(32) })
                }
            // The header is a quarter of the row (wider if its buttons need it); 24dp, then the list panel (16dp in)
            row.addView(header, LinearLayout.LayoutParams(WRAP, MATCH))
            listPanel.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
            listPanel.addView(loadingView, FrameLayout.LayoutParams(MATCH, MATCH))
            listPanel.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
            row.addView(
                listPanel,
                LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                    marginStart = context.dp(24 + 16)
                    bottomMargin = context.dp(32)
                },
            )
            root.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            list.adapter = adapter
            list.playButton = playButton
            clockJob = clockScope.launch { AppClock.now.collect { adapter.refreshTimes() } }
        }

        fun release() {
            clockScope.cancel()
        }

        fun firstFocus(): View? =
            when {
                loading == LoadingState.Success && items.isNotEmpty() -> list
                else -> playButton
            }

        fun bind(state: PlaylistDetailsState) {
            val wasLoading = loading !is LoadingState.Success && loading !is LoadingState.Error
            loading = state.loading
            title.bind(if (state.loading is LoadingState.Success) state.playlist?.name ?: getString(R.string.playlist) else "")
            filterButton.bind(state.filterAndSort.filter)
            sortButton.bind(state.filterAndSort.sortAndDirection)
            val filterCount = state.filterAndSort.filter.countFilters(DefaultPlaylistItemsOptions)
            canMove = state.canEdit && state.filterAndSort.sortAndDirection.sort == ItemSortBy.DEFAULT && filterCount == 0
            when (val l = state.loading) {
                is LoadingState.Error -> {
                    list.visibility = View.GONE
                    loadingView.visibility = View.GONE
                    emptyView.visibility = View.GONE
                    errorView?.let { listPanel.removeView(it) }
                    errorView =
                        ErrorView(requireContext(), l, onSendLogs = errorViewModel::sendLogs).also {
                            listPanel.addView(it, FrameLayout.LayoutParams(MATCH, WRAP))
                        }
                }

                LoadingState.Pending, LoadingState.Loading -> {
                    errorView?.let { listPanel.removeView(it) }
                    errorView = null
                    list.visibility = View.GONE
                    emptyView.visibility = View.GONE
                    loadingView.visibility = View.VISIBLE
                }

                LoadingState.Success -> {
                    errorView?.let { listPanel.removeView(it) }
                    errorView = null
                    loadingView.visibility = View.GONE
                    if (state.items.isNotEmpty()) {
                        emptyView.visibility = View.GONE
                        list.visibility = View.VISIBLE
                        adapter.setItems(state.items)
                        watchPager("items", state.items) {
                            adapter.refresh()
                            updateFocused()
                        }
                    } else {
                        list.visibility = View.GONE
                        emptyView.visibility = View.VISIBLE
                    }
                }
            }
            items = state.items
            updateFocused()
            // Focus the list (Play when empty) once the entries or the error arrive
            if (wasLoading && (state.loading is LoadingState.Success || state.loading is LoadingState.Error)) {
                root.doOnPreDraw { firstFocus()?.let(::requestInitialFocus) }
            }
        }

        fun onFocusItem(index: Int) {
            focusedIndex = index
            updateFocused()
        }

        private var backdropItem: BaseItem? = null

        private fun updateFocused() {
            val item = items.getOrNull(focusedIndex)
            focusedTitle.text = item?.title ?: ""
            focusedSubtitle.text = item?.subtitle ?: ""
            val premiere = item?.data?.premiereDate
            if (item?.type == BaseItemKind.EPISODE && premiere != null) {
                focusedDate.visibility = View.VISIBLE
                focusedDate.text = formatDateTime(premiere)
            } else {
                focusedDate.visibility = View.GONE
            }
            focusedOverview.setText(item?.data?.overview ?: "")
            if (item != null && item !== backdropItem) {
                backdropItem = item
                viewModel.updateBackdrop(item)
            }
        }

        /** The entries' list: leaving it in any direction goes to Play (`onExit`) */
        private inner class PlaylistList(
            context: Context,
        ) : VerticalGridView(context) {
            var playButton: View? = null

            init {
                clipChildren = false
                clipToPadding = false
                itemAnimator = null
                val h = context.dp(16)
                val v = context.dp(MenuDialog.DIALOG_LIST_EDGE_DP)
                setPadding(h, v, h, v)
                windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
                focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
                clipToOwnHeight()
                // Leaving at either end reaches focusSearch below (to Play); a Leanback list keeps focus at its ends
                (layoutManager as? GridLayoutManager)?.setFocusOutAllowed(true, true)
            }

            override fun focusSearch(
                focused: View?,
                direction: Int,
            ): View? {
                val result = super.focusSearch(focused, direction)
                if (result != null && result !== focused && !isInside(result)) {
                    playButton?.takeIf { it.isShown }?.let { return it }
                }
                return result
            }

            private fun isInside(view: View): Boolean {
                var v: View? = view
                while (v != null) {
                    if (v === this) return true
                    v = v.parent as? View
                }
                return false
            }
        }

        private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.Holder>() {
            inner class Holder(
                val row: PlaylistItemView,
            ) : RecyclerView.ViewHolder(row)

            private var data: List<BaseItem?> = emptyList()
            private var count = 0

            init {
                setHasStableIds(true)
            }

            fun setItems(items: List<BaseItem?>) {
                if (items === data) {
                    refresh()
                    return
                }
                data = items
                count = items.size
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

            fun refresh() {
                val n = data.size
                if (n != count) {
                    count = n
                    @Suppress("NotifyDataSetChanged")
                    notifyDataSetChanged()
                } else if (n > 0) {
                    notifyItemRangeChanged(0, n, refreshPayload)
                }
            }

            fun refreshTimes() {
                for (i in 0 until list.childCount) (list.getChildAt(i) as? PlaylistItemView)?.refreshTime()
            }

            override fun getItemCount(): Int = count

            override fun getItemId(position: Int): Long = position.toLong()

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): Holder =
                Holder(PlaylistItemView(parent.context)).apply {
                    row.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }

            override fun onBindViewHolder(
                holder: Holder,
                position: Int,
            ) {
                val item = if (position in 0 until count) data[position] else null
                holder.row.bind(position, item, canMove, position > 0, position < count - 1, count)
            }

            private val refreshPayload = Any()
        }

        /** `PlaylistItem`: the list row (at least 80dp tall) and its move / More buttons */
        private inner class PlaylistItemView(
            context: Context,
        ) : LinearLayout(context) {
            private var index = 0
            private var item: BaseItem? = null
            private val listItem = AppListItem(context)
            private val number =
                AppCompatTextView(context).apply {
                    applyTextRole(TextRole.LABEL_LARGE)
                    gravity = Gravity.END
                }
            private var numberWidthPx = -1
            private val image = ItemImageView(context)
            private val duration = AppCompatTextView(context).apply { applyTextRole(TextRole.LABEL_LARGE) }
            private val endsAt = AppCompatTextView(context).apply { applyTextRole(TextRole.BODY_SMALL) }
            private val upButton = glyphButton(context, R.string.fa_caret_up) { move(MoveDirection.UP) }
            private val downButton = glyphButton(context, R.string.fa_caret_down) { move(MoveDirection.DOWN) }
            private val moreButton = glyphButton(context, R.string.fa_ellipsis_vertical) { item?.let { showMenu(index, it, false, this) } }

            init {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                minimumHeight = context.dp(80)
                showDividers = SHOW_DIVIDER_MIDDLE
                dividerDrawable = SpaceDrawable(context.dp(16))
                val leading =
                    LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        showDividers = SHOW_DIVIDER_MIDDLE
                        dividerDrawable = SpaceDrawable(context.dp(16))
                        addView(number, LayoutParams(WRAP, WRAP))
                        addView(image, LayoutParams(context.dp(160), context.dp(72)))
                    }
                listItem.setLeading(ListLeading.Custom(leading))
                val trailing =
                    LinearLayout(context).apply {
                        orientation = VERTICAL
                        addView(duration, LayoutParams(WRAP, WRAP))
                        addView(endsAt, LayoutParams(WRAP, WRAP))
                    }
                listItem.setTrailingView(trailing)
                listItem.onContentColor = { color ->
                    number.setTextColor(color)
                    duration.setTextColor(color)
                    endsAt.setTextColor(color)
                }
                listItem.headlineView.ellipsize = TextUtils.TruncateAt.MARQUEE
                listItem.supportingView.ellipsize = TextUtils.TruncateAt.MARQUEE
                listItem.setOnClickListener { item?.let { play(index, it, false) } }
                listItem.setOnLongClickListener {
                    item?.let { showMenu(index, it, true, listItem) }
                    true
                }
                listItem.setOnFocusChangeListener { _, hasFocus ->
                    // Long titles scroll while focused (`enableMarquee`)
                    listItem.headlineView.isSelected = hasFocus
                    listItem.supportingView.isSelected = hasFocus
                }
                addView(listItem, LayoutParams(0, WRAP, 1f))
                val buttons =
                    LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        clipChildren = false
                        showDividers = SHOW_DIVIDER_MIDDLE
                        dividerDrawable = SpaceDrawable(context.dp(4))
                        addView(upButton)
                        addView(downButton)
                        addView(moreButton)
                    }
                addView(buttons, LayoutParams(WRAP, WRAP))
            }

            override fun onRequestFocusInDescendants(
                direction: Int,
                previouslyFocusedRect: android.graphics.Rect?,
            ): Boolean = listItem.requestFocus(direction, previouslyFocusedRect)

            override fun requestChildFocus(
                child: View?,
                focused: View?,
            ) {
                super.requestChildFocus(child, focused)
                onFocusItem(index)
            }

            fun bind(
                index: Int,
                item: BaseItem?,
                canMove: Boolean,
                moveUpAllowed: Boolean,
                moveDownAllowed: Boolean,
                count: Int,
            ) {
                this.index = index
                this.item = item
                // Every row's number is as wide as the widest one, so pictures and titles line up ("1." vs "10.")
                val digits = count.coerceAtLeast(1).toString().length
                val digitWidth = (0..9).maxOf { number.paint.measureText(it.toString()) }
                val numberWidth = kotlin.math.ceil(digitWidth * digits + number.paint.measureText(".")).toInt()
                if (numberWidth != numberWidthPx) {
                    numberWidthPx = numberWidth
                    number.setWidth(numberWidth)
                }
                listItem.setHeadline(item?.title ?: "")
                listItem.setSupporting(item?.subtitle ?: "", reserveLine = true)
                number.text = "${index + 1}."
                val imageType = if (item != null && ImageType.THUMB in item.data.imageTags.orEmpty()) ImageType.THUMB else ImageType.PRIMARY
                image.bind(
                    ItemImageState(
                        imageUrl = CardImages.itemImageUrl(context, item, imageType, fillWidthPx = context.dp(160)),
                        name = item?.name,
                        showOverlay = true,
                        favorite = item?.data?.userData?.isFavorite ?: false,
                        watched = item?.data?.userData?.played ?: false,
                        unwatchedCount = item?.countBadge ?: -1,
                        watchedPercent = 0.0,
                        numberOfVersions = item?.data?.mediaSourceCount ?: 0,
                        accent = pageStyle.accentFor(item),
                        useFallbackText = false,
                    ),
                    pageStyle,
                )
                upButton.visibility = if (canMove) View.VISIBLE else View.GONE
                downButton.visibility = if (canMove) View.VISIBLE else View.GONE
                upButton.isActionEnabled = moveUpAllowed
                downButton.isActionEnabled = moveDownAllowed
                refreshTime()
            }

            fun refreshTime() {
                val runtime =
                    item
                        ?.data
                        ?.runTimeTicks
                        ?.ticks
                        ?.roundMinutes
                if (runtime == null) {
                    duration.visibility = View.GONE
                    endsAt.visibility = View.GONE
                    return
                }
                duration.visibility = View.VISIBLE
                endsAt.visibility = View.VISIBLE
                duration.text = context.resources.formatDuration(runtime)
                val end =
                    AppClock.now.value
                        .toLocalTime()
                        .plusSeconds(runtime.inWholeSeconds)
                endsAt.text = context.getString(R.string.ends_at, formatTime(context, end))
            }

            private fun move(direction: MoveDirection) {
                val i = item ?: return
                viewModel.onMoveItem(index, i.id, direction)
                // Focus follows the entry: the same button one row up or down
                val target = index + if (direction == MoveDirection.UP) -1 else 1
                val focusedButton = if (direction == MoveDirection.UP) 0 else 1
                (list.findViewHolderForAdapterPosition(target)?.itemView as? PlaylistItemView)?.focusButton(focusedButton)
            }

            fun focusButton(which: Int) {
                (if (which == 0) upButton else downButton).requestFocus()
            }
        }
    }

    /** `Button(contentHeight = 24.dp) { Text(glyph, fontFamily = FontAwesome) }` */
    private fun glyphButton(
        context: Context,
        glyph: Int,
        onClick: () -> Unit,
    ): AppButton =
        AppButton(context).apply {
            setContentPaddingDp(4f, 4f, 4f, 4f)
            contentHeightDp = 24f
            setLabel(context.getString(glyph))
            labelView.typeface = AppFonts.icons(context)
            setOnClickListener { onClick() }
        }
}

/**
 * `GridTitle` (playlist page): WeaselTV: the "Playlist" eyebrow, the title uppercase in the page title type (shrinking to
 * fit, 16dp in, clear of the clock), then the section rule reaching the page gutters; other themes: displaySmall
 * centred on one line.
 */
class GridTitleView(
    context: Context,
) : LinearLayout(context) {
    private val neon = AppTheme.isNeon
    private val eyebrow = AppCompatTextView(context)
    private val title: TextView =
        if (neon) {
            FittedTitleView(context, maxLines = 3)
        } else {
            AppCompatTextView(context).apply {
                applyTextRole(TextRole.DISPLAY_SMALL)
                setTextColor(AppTheme.palette.onBackground)
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
        }
    private val rule = NeonRuleView(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        if (neon) {
            val inset = context.dp(16)
            eyebrow.applyNeonEyebrow(context.getString(R.string.playlist), pageAccent())
            addView(
                eyebrow,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = inset
                    marginEnd =
                        inset
                },
            )
            title.setTextColor(AppTheme.palette.onSurface)
            addView(
                title,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = inset
                    marginEnd = TimeDisplayView.headerEndPaddingPx(context)
                    topMargin = context.dp(2)
                },
            )
            rule.thicknessDp = NeonSizes.RULE
            rule.glowHeightDp = NeonGlowSpec.RULE
            rule.glowStrength = .35f
            addView(
                rule,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(6)
                    marginStart = -context.neonGutterStartPx()
                    marginEnd = -context.neonGutterEndPx()
                },
            )
        } else {
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun pageAccent(): Int = com.github.damontecres.wholphin.leanback.theme.NeonColors.VOLT

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (neon) {
            val accent = resolveNeonScope().accentOrDefault
            eyebrow.setTextColor(accent)
        }
    }

    fun bind(text: String) {
        if (neon) {
            (title as FittedTitleView).setStyledText(text.uppercase(), NeonType.pageTitle())
        } else {
            title.text = text
        }
    }
}
