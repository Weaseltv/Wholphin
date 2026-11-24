package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardGridAdapter
import com.github.damontecres.wholphin.leanback.widget.card.CardRowStyle
import com.github.damontecres.wholphin.leanback.widget.card.CardRowView
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.ItemDetailsDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.PlaylistPickerDialog
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.RequestPager
import kotlinx.coroutines.Job
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Shared pieces of the no-Compose details pages (DETAILS area, 2026-11-22): the page base class, the page list,
 * the action button row and small layout helpers. Pages are in this package; contract in
 * runbooks/no-compose/CONTRACT.md.
 */

/**
 * Base of every details page: a root that shows the loading page, the error page or the content (added once, when the
 * data first arrives, so the detail viewport measures it as the Compose `DetailPageViewport` did), page activity
 * ([onPageActive] / [onPageInactive], the old `LifecycleResumeEffect`: the page is resumed and is the visible page),
 * and the dialogs every details page opens (overview, playlist picker, context menu).
 */
abstract class DetailPageFragment : DestinationFragment() {
    @Inject
    lateinit var navigationManager: NavigationManager

    protected val errorViewModel: ErrorViewModel by viewModels()

    /** "Add to playlist" (the old `AddPlaylistViewModel` every details page had) */
    protected val playlistViewModel: AddPlaylistViewModel by viewModels()

    /** The page's root; the loading, error and content views go in it */
    protected lateinit var root: FrameLayout
        private set

    private var loadingView: View? = null
    private var errorView: View? = null

    /** The content, once [showContent] added it */
    protected var content: View? = null
        private set

    private var active = false
    private val pagerWatches = HashMap<String, Pair<List<*>, Job>>()

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
        content = null
        loadingView = null
        errorView = null
        showLoading()
        return root
    }

    override fun onDestroyView() {
        content = null
        loadingView = null
        errorView = null
        pagerWatches.clear()
        super.onDestroyView()
    }

    /**
     * Call [onChange] whenever the pager behind [list] loads a page or changes size (the Compose rows recomposed from
     * the pager's state); watching [key] moves to a new list when the page gets one. Plain lists are not watched.
     */
    protected fun watchPager(
        key: String,
        list: List<*>,
        onChange: () -> Unit,
    ) {
        val existing = pagerWatches[key]
        if (existing != null && existing.first === list) return
        existing?.second?.cancel()
        val pager = list as? RequestPager<*>
        if (pager == null) {
            pagerWatches.remove(key)
            return
        }
        pagerWatches[key] = list to pager.changes.collectWhileStarted { onChange() }
    }

    /** The loading page (`LoadingPage`); focus waits on the shell's sink meanwhile */
    protected fun showLoading() {
        errorView?.visibility = View.GONE
        content?.visibility = View.GONE
        val loading =
            loadingView ?: LoadingPageView(requireContext(), focusEnabled = false).also {
                loadingView = it
                root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        loading.visibility = View.VISIBLE
    }

    /** The error page (`ErrorMessage`) */
    protected fun showError(error: DataLoadingState.Error) = showError(error.message, error.exception)

    protected fun showError(error: LoadingState.Error) = showError(error.message, error.exception)

    protected fun showError(
        message: String?,
        exception: Throwable?,
    ) {
        loadingView?.visibility = View.GONE
        content?.visibility = View.GONE
        errorView?.let { root.removeView(it) }
        val view = ErrorView(requireContext(), message, exception, onSendLogs = errorViewModel::sendLogs)
        errorView = view
        root.addView(view, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        requestInitialFocus(view.sendLogsButton)
    }

    /**
     * Show the content, creating it with [create] the first time. Returns true when it was created now (the page
     * then takes its first focus).
     */
    protected fun showContent(create: () -> View): Boolean {
        loadingView?.let {
            root.removeView(it)
            loadingView = null
        }
        errorView?.let {
            root.removeView(it)
            errorView = null
        }
        val existing = content
        if (existing != null) {
            existing.visibility = View.VISIBLE
            return false
        }
        val view = create()
        content = view
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        return true
    }

    override fun onResume() {
        super.onResume()
        updateActive()
    }

    override fun onPause() {
        super.onPause()
        updateActive()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateActive()
    }

    private fun updateActive() {
        val now = isResumed && !isHidden && view != null
        if (now == active) return
        active = now
        if (now) onPageActive() else onPageInactive()
    }

    /** Whether the page is resumed and visible (between [onPageActive] and [onPageInactive]) */
    protected val isPageActive: Boolean get() = active

    /** The page resumed or came back into view (the old `LifecycleResumeEffect` body) */
    protected open fun onPageActive() {}

    /** The page paused or went under another page (the old `onPauseOrDispose`) */
    protected open fun onPageInactive() {}

    /** The item information dialog (`ItemDetailsDialog`) */
    protected fun showOverviewDialog(
        info: ItemDetailsDialogInfo,
        showFilePath: Boolean,
        opener: View? = view?.findFocus(),
    ) {
        ItemDetailsDialog(requireContext(), info, showFilePath, opener = opener).show()
    }

    /** "Add to playlist" (`PlaylistDialog`): loads the playlists, then adds [itemId] to the chosen or a new one */
    protected fun showPlaylistDialog(
        itemId: UUID,
        opener: View? = view?.findFocus(),
    ) {
        playlistViewModel.loadPlaylists()
        lateinit var dialog: PlaylistPickerDialog
        dialog =
            PlaylistPickerDialog(
                context = requireContext(),
                title = getString(com.github.damontecres.wholphin.R.string.add_to_playlist),
                state = playlistViewModel.playlistState,
                onClick = {
                    playlistViewModel.addToPlaylist(it.id, itemId)
                    dialog.requestDismiss()
                },
                onSearch = playlistViewModel::loadPlaylists,
                createEnabled = true,
                onCreatePlaylist = {
                    playlistViewModel.createPlaylistAndAddItem(it, itemId)
                    dialog.requestDismiss()
                },
                elevationDp = 3f,
                opener = opener,
            )
        dialog.show()
    }

    /**
     * The context menu ([ContextMenuDialog]); a kid's page has none ([KidTitlePage.contextMenuFor]): OK still opens or
     * plays the item.
     */
    protected fun showContextMenu(
        menu: ContextMenu,
        kidPage: Boolean,
        getMediaSource: ((BaseItemDto, ItemPlayback?) -> MediaSourceInfo?)?,
        preferredSubtitleLanguage: String?,
        opener: View? = view?.findFocus(),
    ) {
        val allowed = KidTitlePage.contextMenuFor(kidPage, menu) ?: return
        ContextMenuDialog.show(
            context = requireContext(),
            contextMenu = allowed,
            getMediaSource = getMediaSource,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            onDismissRequest = {},
            opener = opener,
        )
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** The page's gutters on the WeaselTV theme (the shell's content padding that section rules reach into), in px */
internal fun Context.neonGutterStartPx(): Int = if (AppTheme.isNeon) dp(CardDimens.NEON_RULE_INSET_START_DP) else 0

internal fun Context.neonGutterEndPx(): Int = if (AppTheme.isNeon) dp(CardDimens.NEON_RULE_INSET_END_DP) else 0

/**
 * One item of a [DetailPageList]: [key] identifies it (and is its own view type, so each row keeps its own view and
 * card adapter, as each Compose row kept its own state), [create] makes its view once, [bind] updates it.
 */
class DetailRowSpec(
    val key: String,
    val create: (Context) -> View,
    val bind: (View) -> Unit = {},
)

/** Adapter of a [DetailPageList]: rows by key; every submit rebinds the rows in place */
class DetailListAdapter : RecyclerView.Adapter<DetailListAdapter.Holder>() {
    class Holder(
        view: View,
    ) : RecyclerView.ViewHolder(view)

    private var rows: List<DetailRowSpec> = emptyList()
    private val types = HashMap<String, Int>()
    private val createByType = HashMap<Int, (Context) -> View>()
    private val ids = HashMap<String, Long>()

    init {
        setHasStableIds(true)
    }

    val keys: List<String> get() = rows.map { it.key }

    fun submit(newRows: List<DetailRowSpec>) {
        val old = rows
        rows = newRows
        newRows.forEach { spec ->
            val type = types.getOrPut(spec.key) { types.size + 1 }
            createByType[type] = spec.create
        }
        DiffUtil
            .calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size

                    override fun getNewListSize() = newRows.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = old[oldItemPosition].key == newRows[newItemPosition].key

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = false
                },
                false,
            ).dispatchUpdatesTo(this)
    }

    fun indexOf(key: String): Int = rows.indexOfFirst { it.key == key }

    override fun getItemCount(): Int = rows.size

    override fun getItemId(position: Int): Long = ids.getOrPut(rows[position].key) { ids.size + 1L }

    override fun getItemViewType(position: Int): Int = types.getValue(rows[position].key)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val view = createByType.getValue(viewType).invoke(parent.context)
        if (view.layoutParams !is RecyclerView.LayoutParams) {
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return Holder(view)
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        rows[position].bind(holder.itemView)
    }
}

/**
 * The vertical list of a details page (the old `LazyColumn`): Leanback's [VerticalGridView], [spacingDp] between
 * items, never clipping card glows sideways. On the WeaselTV theme it scrolls only as far as needed to show the focused
 * item whole (`KeepVisibleBringIntoViewSpec`, the header's `bringIntoView` included); other themes keep the focused
 * item at 30 % of the page (the TV default pivot), with the first item staying at the top.
 *
 * Each item offers only its own restored focus target to D-pad moves (rows their last focused card, button rows their
 * last focused button), so UP / DOWN between items behave like the Compose `focusRestorer`s.
 */
class DetailPageList(
    context: Context,
    spacingDp: Float = 16f,
) : VerticalGridView(context) {
    val listAdapter = DetailListAdapter()
    private val clip = Rect()

    init {
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
        setHasFixedSize(false)
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        setVerticalSpacing(context.dp(spacingDp))
        setItemViewCacheSize(20)
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        itemAlignmentOffset = 0
        itemAlignmentOffsetPercent = 0f
        isItemAlignmentOffsetWithPadding = true
        if (AppTheme.isNeon) {
            focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
        } else {
            focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED
            windowAlignmentOffset = 0
            windowAlignmentOffsetPercent = 30f
        }
        adapter = listAdapter
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)
        // Clip only along the scrolling axis (Compose clipScrollableContainer allows room sideways for glows)
        val extra = context.dp(48)
        clip.set(-extra, 0, r - l + extra, b - t)
        clipBounds = clip
    }

    /** The view of the item [key] if it is laid out */
    fun viewFor(key: String): View? {
        val index = listAdapter.indexOf(key)
        if (index < 0) return null
        return findViewHolderForAdapterPosition(index)?.itemView
    }
}

/**
 * A horizontally scrolling row of buttons (the old `LazyRow` / `Row(horizontalScroll)` of action buttons with a
 * `focusRestorer`): [strip] holds the buttons [spacingDp] apart.
 *
 * Focus: entering the row (UP / DOWN from a row) lands on the last focused button, else on [firstFocus] (Play), as
 * `focusRestorer(firstFocus)` did. LEFT / RIGHT move between buttons and stop at the ends; LEFT at the first button
 * leaves the page (the side menu).
 *
 * Scrolling: just far enough to show the focused button with [glowRoomDp] beside it (`KeepFocusRoomOnScroll`) on the
 * WeaselTV theme; other themes put the focused button at 30 % of the row (the TV default pivot). A button that grows
 * when focused (expanding labels) is scrolled into view again once it has grown.
 */
open class ActionButtonRow(
    context: Context,
    spacingDp: Float = 16f,
    private val glowRoomDp: Float = 16f,
) : HorizontalScrollView(context) {
    val strip: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            gravity = android.view.Gravity.CENTER_VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = SpaceDrawable(context.dp(spacingDp))
        }

    /** The button focus starts on when the row has not been focused yet (`firstFocus`) */
    var firstFocus: View? = null

    private var lastFocused: View? = null
    private val clip = Rect()

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        clipChildren = false
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isSmoothScrollingEnabled = true
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Add [button] at the end of the row */
    fun addButton(button: View) {
        strip.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** The button focus returns to when the row is entered */
    val restoreTarget: View?
        get() =
            lastFocused?.takeIf { usable(it) }
                ?: firstFocus?.takeIf { usable(it) }
                ?: firstFocusable(strip)

    private fun usable(view: View): Boolean = view.isFocusable && view.isShown && isDescendant(view)

    private fun firstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.isFocusable) return child
            if (child is ViewGroup && child.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
                firstFocusable(child)?.let { return it }
            }
        }
        return null
    }

    /** Forget the last focused button (eg when the row's content changes completely) */
    fun resetFocusMemory() {
        lastFocused = null
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
        if (!hasFocus() && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
            val target = restoreTarget
            if (target != null) {
                target.addFocusables(views, direction, focusableMode)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: Rect?,
    ): Boolean {
        val target = restoreTarget
        if (target != null && target.requestFocus(direction, previouslyFocusedRect)) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Stop at the ends of the row (no wrap); LEFT at the first button leaves to the side menu
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val focused = findFocus()
            if (focused != null && focused.focusSearch(View.FOCUS_RIGHT)?.let { isDescendant(it) } != true) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDescendant(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val width = width
        val scrollX = scrollX
        val maxScroll = (getChildAt(0).width + paddingLeft + paddingRight - width).coerceAtLeast(0)
        val target =
            if (AppTheme.isNeon) {
                val room = context.dp(glowRoomDp)
                val left = scrollX + paddingLeft
                val right = scrollX + width - paddingRight
                when {
                    rect.left - room < left -> scrollX + (rect.left - room - left)
                    rect.right + room > right -> scrollX + (rect.right + room - right)
                    else -> scrollX
                }
            } else {
                rect.left - (width * .3f).roundToInt()
            }
        return target.coerceIn(0, maxScroll) - scrollX
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)
        val extra = context.dp(30)
        clip.set(0, -extra, r - l, b - t + extra)
        clipBounds = clip
        // A button that grows on focus (expanding label) widens the row: show it again whole
        findFocus()?.let { focused ->
            var child: View? = focused
            while (child != null && child.parent !== strip) child = child.parent as? View
            if (child != null) {
                val rect = Rect(child.left, child.top, child.right, child.bottom)
                val delta = computeScrollDeltaToGetChildRectOnScreen(rect)
                if (delta != 0) post { smoothScrollBy(delta, 0) }
            }
        }
    }
}

/** An invisible divider of a fixed width (spacing between LinearLayout children without margins) */
internal class SpaceDrawable(
    private val sizePx: Int,
) : android.graphics.drawable.Drawable() {
    override fun draw(canvas: android.graphics.Canvas) {}

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSPARENT

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx
}

/**
 * A vertical column [fraction] as wide as the width it is given (Compose `fillMaxWidth(fraction)`), its children
 * [spacingDp] apart (`Arrangement.spacedBy`).
 */
open class FractionColumn(
    context: Context,
    var fraction: Float = 1f,
    spacingDp: Float = 0f,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        if (spacingDp > 0f) {
            showDividers = SHOW_DIVIDER_MIDDLE
            dividerDrawable = SpaceDrawable(context.dp(spacingDp))
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        if (fraction < 1f && mode != MeasureSpec.UNSPECIFIED) {
            val w = (MeasureSpec.getSize(widthMeasureSpec) * fraction).roundToInt()
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** Add [view] with a start padding of [startDp] (the old `Modifier.padding(start = …)`) */
    fun addPadded(
        view: View,
        startDp: Float = HeaderDimens.START_PADDING,
        topDp: Float = 0f,
        bottomDp: Float = 0f,
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    ): View {
        addView(
            view,
            LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(startDp)
                topMargin = context.dp(topDp)
                bottomMargin = context.dp(bottomDp)
            },
        )
        return view
    }
}

/** A typography slot scaled by [scale] (`TextStyle.scaled`: size and line height) */
internal fun scaledRole(
    role: TextRole,
    scale: Float,
): ControlTextStyle =
    if (scale == 1f) {
        ControlTextStyle.of(role)
    } else {
        ControlTextStyle.of(role, sizeSp = (if (AppTheme.useWeaselFonts) role.neonSp else role.stockSp) * scale)
    }

/** A plain text in [style] and the onSurface colour */
internal fun detailText(
    context: Context,
    style: ControlTextStyle,
    maxLines: Int = Int.MAX_VALUE,
): TextView =
    androidx.appcompat.widget.AppCompatTextView(context).apply {
        applyTextStyle(style)
        setTextColor(AppTheme.palette.onSurface)
        this.maxLines = maxLines
        if (maxLines != Int.MAX_VALUE) ellipsize = android.text.TextUtils.TruncateAt.END
    }

/** Set a text size in sp keeping the typeface (Compose `fontSize = …` on a styled Text) */
internal fun TextView.setSp(sp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)

/** Font Awesome glyphs in a TextView */
internal fun TextView.useIconFont() {
    typeface = AppFonts.icons(context)
}

/**
 * Shows a pager-backed list ([com.github.damontecres.wholphin.util.RequestPager]) in a [CardRowView] with a
 * positional adapter that reads an item only when its card is bound (as the Compose `LazyRow` read only composed
 * items), so a long list never loads every page at once. [CardGridAdapter.refresh] rebinds the shown cards when a page
 * arrives. The title gets the item count on the WeaselTV theme, as `ItemRow` did.
 */
internal fun CardRowView.showPaged(
    title: String,
    titleAccent: Int,
    pageStyle: PageStyle,
    style: CardRowStyle,
    adapter: CardGridAdapter<BaseItem>,
    items: List<BaseItem?>,
) {
    setup(title, titleAccent, pageStyle, style)
    if (strip.adapter !== adapter) strip.adapter = adapter
    adapter.setItems(items)
    val padding = style.resolvedPadding()
    titleView.bind(
        title = title,
        accent = titleAccent,
        style = style.title,
        count = items.size.takeIf { AppTheme.isNeon && style.showCount },
        insetStartPx = style.contentInsetStartPx,
        insetEndPx = style.contentInsetEndPx,
        titleStartPaddingDp = style.title.titleStartPaddingDp ?: if (AppTheme.isNeon) padding.startDp else 8f,
    )
}

/** `max` of two ints, for layout math */
internal fun maxOf2(
    a: Int,
    b: Int,
): Int = max(a, b)
