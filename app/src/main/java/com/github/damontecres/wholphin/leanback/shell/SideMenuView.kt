package com.github.damontecres.wholphin.leanback.shell

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import coil3.load
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.RailAccents
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.sectionAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.card.AvatarImages
import com.github.damontecres.wholphin.leanback.widget.card.clipToOwnHeight
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkIds
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkRail
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkStep
import com.github.damontecres.wholphin.ui.nav.HOME_INDEX
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.SEARCH_INDEX
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.nav.WeaselNavIcons
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shared motion of the shell */
internal object ShellMotion {
    /** Compose `spring(stiffness = Spring.StiffnessMedium)` (critically damped), the menu and page slide */
    const val SPRING_MS = 260L

    val spring =
        TimeInterpolator { x ->
            // x(t) = 1 - (1 + wt) e^-wt with w = sqrt(1500), scaled so it ends exactly at 1
            val w = sqrt(1500.0)
            val t = x * SPRING_MS / 1000.0
            val end = 1 - (1 + w * SPRING_MS / 1000.0) * exp(-w * SPRING_MS / 1000.0)
            ((1 - (1 + w * t) * exp(-w * t)) / end).toFloat()
        }
}

/** What the side menu shows; built by the shell from `NavDrawerViewModel` and the request badge */
internal data class SideMenuModel(
    val user: JellyfinUser,
    val server: JellyfinServer,
    val userImageUrl: String?,
    val items: List<NavDrawerItem>,
    val moreItems: List<NavDrawerItem>,
    val selectedIndex: Int,
    val moreExpanded: Boolean,
    val kidSession: Boolean,
    val badgeCount: Int,
)

/**
 * The side menu (the old Compose `NavDrawer` + `ModalNavigationDrawer`) as Views: brand (WeaselTV theme), profile,
 * then a scrolling list of Search, Home, the pinned items, More and its items, and Settings pinned to the bottom.
 *
 * Collapsed it is a 72dp rail (64dp on other themes) showing glyphs; it expands to 240dp (224dp) while it has focus,
 * the shell sliding the page right by the difference. Focus rules (entering lands on the current section, RIGHT
 * returns to the page) are applied by the shell's focus routing, see `AppShell`.
 */
@SuppressLint("ViewConstructor")
internal class SideMenuView(
    context: Context,
) : FrameLayout(context) {
    interface Listener {
        fun onProfileClick()

        fun onSearchClick()

        fun onHomeClick()

        fun onItemClick(
            index: Int,
            item: NavDrawerItem,
        )

        fun onSettingsClick()
    }

    var listener: Listener? = null

    private val neon get() = AppTheme.isNeon

    /** Collapsed / expanded widths: 72 / 240 on the WeaselTV theme (`NeonBoard.Size`), 64 / 224 elsewhere */
    val collapsedWidthPx: Int get() = context.dp(if (neon) 72 else 64)
    val expandedWidthPx: Int get() = context.dp(if (neon) 240 else 224)

    /** Room to the right of the sheet for its shadow when open */
    private val shadowRoom = context.dp(24)

    private val column =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
    private val brand = FrameLayout(context)
    private val brandImage = ImageView(context)
    private val profileRow = RailRow(context, twoLine = true)
    private val scroll = RailScrollView(context)
    private val list =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

    private val rows = LinkedHashMap<String, RailRow>()
    private var rowKeys: List<String> = emptyList()
    private var model: SideMenuModel? = null

    /** Open (expanded): the menu has focus */
    var isOpen = false
        private set

    /** 0 collapsed .. 1 expanded, animated */
    var openFraction = 0f
        private set

    /** Called with the animated fraction (the shell slides the pages) */
    var onOpenFractionChanged: ((Float) -> Unit)? = null

    private var animator: ValueAnimator? = null
    private val sheetPaint = Paint()
    private val edgePaint = Paint()
    private val shadowPaint = Paint()

    /** The coach-mark tour's view of the menu, see [CoachMarkRail] */
    val coachMarkRail: CoachMarkRail =
        object : CoachMarkRail {
            override fun has(step: CoachMarkStep): Boolean {
                val m = model ?: return false
                return when (step) {
                    CoachMarkStep.MENU, CoachMarkStep.PROFILE, CoachMarkStep.SEARCH, CoachMarkStep.HOME -> true
                    CoachMarkStep.WATCHLIST -> NavDrawerItem.Favorites in m.items
                    CoachMarkStep.REQUESTS -> NavDrawerItem.Discover in m.items
                    CoachMarkStep.LIBRARIES -> m.items.any { it is ServerNavDrawerItem }
                    CoachMarkStep.SETTINGS -> !m.kidSession
                    else -> false
                }
            }

            override suspend fun bringIntoView(step: CoachMarkStep) {
                val m = model ?: return
                // The whole menu from the top, so profile, Search and Home all show as the menu steps begin
                if (step == CoachMarkStep.MENU || step == CoachMarkStep.PROFILE) {
                    if (scroll.scrollY != 0) scroll.smoothScrollTo(0, 0)
                    return
                }
                val keys =
                    when (step) {
                        CoachMarkStep.SEARCH -> listOf(KEY_SEARCH)
                        CoachMarkStep.HOME -> listOf(KEY_HOME)
                        CoachMarkStep.WATCHLIST -> listOf(itemKey(NavDrawerItem.Favorites))
                        CoachMarkStep.REQUESTS -> listOf(itemKey(NavDrawerItem.Discover))
                        CoachMarkStep.LIBRARIES -> m.items.filterIsInstance<ServerNavDrawerItem>().map { itemKey(it) }
                        CoachMarkStep.SETTINGS -> if (m.kidSession) emptyList() else listOf(KEY_SETTINGS)
                        else -> emptyList()
                    }
                val views = keys.mapNotNull { rows[it] }
                if (views.isEmpty()) return
                val top = views.minOf { it.top }
                val bottom = views.maxOf { it.bottom }
                val inView = top >= scroll.scrollY && bottom <= scroll.scrollY + scroll.height
                if (!inView) scroll.smoothScrollTo(0, top)
            }
        }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        brand.addView(
            brandImage,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
            ),
        )
        brandImage.adjustViewBounds = true
        brandImage.contentDescription = "WeaselPlex"
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(collapsedWidthPx, LayoutParams.MATCH_PARENT))
        profileRow.setOnClickListener { listener?.onProfileClick() }
        profileRow.setOnKeyListener { _, keyCode, event ->
            // Down from the profile goes to Search even while the list is scrolled (the old onEnter rule)
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                scroll.scrollTo(0, 0)
                rows[KEY_SEARCH]?.requestFocus() ?: false
            } else {
                false
            }
        }
        buildColumn()
    }

    /** Re-applies the theme (sizes, colours, brand) after an app theme change */
    fun onThemeChanged() {
        buildColumn()
        rows.clear()
        rowKeys = emptyList()
        model?.let { bind(it) }
    }

    private fun buildColumn() {
        column.removeAllViews()
        column.layoutParams = (column.layoutParams as LayoutParams).apply { width = currentSheetWidth() }
        val gap = context.dp(4)
        if (neon) {
            column.addView(
                brand,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(44)).apply {
                    topMargin = context.dp(8)
                    bottomMargin = context.dp(4)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
            updateBrand()
        }
        column.addView(
            profileRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(64)).apply {
                if (neon) topMargin = gap
            },
        )
        column.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = gap },
        )
        // Rows don't grow when focused, so the room at both ends only has to cover their glow (RailGlowRoom)
        val glowRoom = if (neon) railGlowRoomPx() else 0
        scroll.setPadding(0, glowRoom, 0, glowRoom)
        if (CoachMarkTargets.enabled) {
            CoachMarkTargets.register(CoachMarkIds.MENU, column)
            CoachMarkTargets.register(CoachMarkIds.PROFILE, profileRow)
        }
    }

    private fun railGlowRoomPx(): Int {
        val a = ApprovedHomeLayout.CARD_APPEARANCE
        return context.dpF(max(10f, a.glowSpreadDp.toFloat()) + a.borderWidthDp / 2f).roundToInt()
    }

    private fun currentSheetWidth(): Int = (collapsedWidthPx + (expandedWidthPx - collapsedWidthPx) * openFraction).roundToInt()

    private fun updateBrand() {
        val name = if (isOpen) WeaselNavIcons.LOCKUP else WeaselNavIcons.MASCOT
        val res = WeaselNavIcons.fixed(context, name) ?: WeaselNavIcons.fixed(context, WeaselNavIcons.MASCOT)
        if (res == null) {
            brand.visibility = GONE
            return
        }
        brand.visibility = VISIBLE
        brandImage.setImageResource(res)
        val expanded = isOpen && res == WeaselNavIcons.fixed(context, WeaselNavIcons.LOCKUP)
        brand.setPadding(if (isOpen) context.dp(12) else 0, 0, 0, 0)
        brandImage.layoutParams =
            (brandImage.layoutParams as LayoutParams).apply {
                // The lockup is ~3.9:1; 36dp keeps it inside the expanded rail's width
                height = if (expanded) context.dp(36) else context.dp(44)
                width = if (expanded) LayoutParams.WRAP_CONTENT else context.dp(44)
            }
    }

    /** Shows [model]; rows are reused by key so a focused row keeps focus */
    fun bind(model: SideMenuModel) {
        this.model = model
        registerTarget(CoachMarkIds.MENU, column)
        registerTarget(CoachMarkIds.PROFILE, profileRow)
        val keys = desiredKeys(model)
        if (keys != rowKeys) rebuildRows(model, keys)
        bindProfile(model)
        bindRows(model)
    }

    private fun desiredKeys(m: SideMenuModel): List<String> =
        buildList {
            add(KEY_SEARCH)
            add(KEY_HOME)
            m.items.forEach { add(itemKey(it)) }
            if (m.moreItems.isNotEmpty()) {
                add(KEY_MORE)
                m.moreItems.forEach { add(MORE_PREFIX + itemKey(it)) }
            }
            // Never selected or a focus target, so leaving it out moves no focus
            if (!m.kidSession) add(KEY_SETTINGS)
        }

    private fun rebuildRows(
        m: SideMenuModel,
        keys: List<String>,
    ) {
        val focusedKey = rows.entries.firstOrNull { it.value.hasFocus() }?.key
        list.removeAllViews()
        val old = HashMap(rows)
        rows.clear()
        val gap = context.dp(4)
        val height = context.dp(if (neon) 44 else 56)
        keys.forEachIndexed { i, key ->
            val row = old.remove(key) ?: RailRow(context, twoLine = false)
            rows[key] = row
            if (key == KEY_SETTINGS && !m.kidSession) {
                // Settings is the rail's footer: pinned to the bottom while everything fits (spacedByWithFooter)
                list.addView(Space(context), LinearLayout.LayoutParams(1, 0, 1f))
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
                    if (i > 0) topMargin = gap
                },
            )
        }
        old.values.forEach { CoachMarkTargets.unregisterAll(it) }
        rowKeys = keys
        // A kid's rail has no Settings and pins nothing, so the list only needs its own height
        scroll.isFillViewport = !m.kidSession
        if (focusedKey != null) rows[focusedKey]?.requestFocus()
    }

    private fun bindProfile(m: SideMenuModel) {
        profileRow.bindProfile(
            user = m.user,
            imageUrl = m.userImageUrl,
            serverName = m.server.name ?: m.server.url,
            open = isOpen,
        )
    }

    private fun bindRows(m: SideMenuModel) {
        val ctx = context
        rows.forEach { (key, row) ->
            when {
                key == KEY_SEARCH -> {
                    row.bindIcon(
                        label = ctx.getString(R.string.search),
                        drawable = WeaselNavIcons.fixed(ctx, WeaselNavIcons.SEARCH) ?: R.drawable.lb_shell_ic_search,
                        accent = RailAccents.SEARCH,
                        selected = m.selectedIndex == SEARCH_INDEX,
                        open = isOpen,
                    )
                    row.setOnClickListener { listener?.onSearchClick() }
                    registerTarget(CoachMarkIds.SEARCH, row)
                }

                key == KEY_HOME -> {
                    row.bindIcon(
                        label = ctx.getString(R.string.home),
                        drawable = WeaselNavIcons.fixed(ctx, WeaselNavIcons.HOME) ?: R.drawable.lb_shell_ic_home,
                        accent = RailAccents.HOME,
                        selected = m.selectedIndex == HOME_INDEX,
                        open = isOpen,
                    )
                    row.setOnClickListener { listener?.onHomeClick() }
                    registerTarget(CoachMarkIds.HOME, row)
                }

                key == KEY_SETTINGS -> {
                    row.bindIcon(
                        label = ctx.getString(R.string.settings),
                        drawable = WeaselNavIcons.fixed(ctx, WeaselNavIcons.SETTINGS) ?: R.drawable.lb_shell_ic_settings,
                        accent = RailAccents.SETTINGS,
                        selected = false,
                        open = isOpen,
                    )
                    row.setOnClickListener { listener?.onSettingsClick() }
                    registerTarget(CoachMarkIds.SETTINGS, row)
                }

                key == KEY_MORE -> {
                    val index = m.items.size
                    row.bindItem(NavDrawerItem.More, selected = false, open = isOpen, moreExpanded = m.moreExpanded, badge = 0)
                    row.setOnClickListener { listener?.onItemClick(index, NavDrawerItem.More) }
                }

                key.startsWith(MORE_PREFIX) -> {
                    val j = m.moreItems.indexOfFirst { MORE_PREFIX + itemKey(it) == key }
                    if (j >= 0) {
                        val item = m.moreItems[j]
                        val index = j + m.items.size
                        row.visibility = if (m.moreExpanded) VISIBLE else GONE
                        row.bindItem(
                            item,
                            selected = m.selectedIndex == index,
                            open = isOpen,
                            moreExpanded = m.moreExpanded,
                            badge = if (item is NavDrawerItem.Discover) m.badgeCount else 0,
                            openContainer = true,
                        )
                        row.setOnClickListener { listener?.onItemClick(index, item) }
                    }
                }

                else -> {
                    val i = m.items.indexOfFirst { itemKey(it) == key }
                    if (i >= 0) {
                        val item = m.items[i]
                        row.bindItem(
                            item,
                            selected = m.selectedIndex == i,
                            open = isOpen,
                            moreExpanded = m.moreExpanded,
                            badge = if (item is NavDrawerItem.Discover) m.badgeCount else 0,
                        )
                        row.setOnClickListener { listener?.onItemClick(i, item) }
                        coachMarkId(item)?.let { registerTarget(it, row) }
                    }
                }
            }
        }
    }

    private fun registerTarget(
        id: String,
        view: View,
    ) {
        if (CoachMarkTargets.enabled) CoachMarkTargets.register(id, view)
    }

    /**
     * The row focus lands on when it comes into the menu: the current section's (the old `onEnter` rule, owner
     * 2026-11-11), or null when that row isn't showing (eg inside a collapsed More)
     */
    fun entryTarget(): View? {
        val m = model ?: return null
        val key =
            when (val i = m.selectedIndex) {
                SEARCH_INDEX -> {
                    KEY_SEARCH
                }

                HOME_INDEX -> {
                    KEY_HOME
                }

                in m.items.indices -> {
                    itemKey(m.items[i])
                }

                in m.items.size until m.items.size + m.moreItems.size -> {
                    if (m.moreExpanded) MORE_PREFIX + itemKey(m.moreItems[i - m.items.size]) else null
                }

                else -> {
                    null
                }
            } ?: return null
        return rows[key]?.takeIf { it.isShown }
    }

    /** Focus the current section's row; false when the menu isn't showing */
    fun focusEntry(): Boolean {
        if (!isShown) return false
        return entryTarget()?.requestFocus() ?: rows[KEY_HOME]?.requestFocus() ?: false
    }

    /** Whether [view] is inside the menu */
    fun contains(view: View?): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    /** Open (focus came in) or close (focus left): animates the width; colours and the brand switch at once */
    fun setOpen(open: Boolean) {
        if (open == isOpen) return
        isOpen = open
        updateBrand()
        model?.let {
            bindProfile(it)
            bindRows(it)
        }
        invalidate()
        animator?.cancel()
        animator =
            ValueAnimator.ofFloat(openFraction, if (open) 1f else 0f).apply {
                duration = (ShellMotion.SPRING_MS * abs((if (open) 1f else 0f) - openFraction)).toLong().coerceAtLeast(1)
                interpolator = ShellMotion.spring
                addUpdateListener { setFraction(it.animatedValue as Float) }
                start()
            }
    }

    /** Snap closed without animating (eg when the menu is hidden) */
    fun closeNow() {
        animator?.cancel()
        if (isOpen) {
            isOpen = false
            updateBrand()
            model?.let {
                bindProfile(it)
                bindRows(it)
            }
        }
        setFraction(0f)
    }

    private fun setFraction(f: Float) {
        openFraction = f
        column.layoutParams = (column.layoutParams as LayoutParams).apply { width = currentSheetWidth() }
        invalidate()
        onOpenFractionChanged?.invoke(f)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = expandedWidthPx + shadowRoom
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!neon) return
        // Neon Board: when expanded a `card` panel with a 1dp `line2` edge and a depth shadow, `stage` collapsed
        val w = currentSheetWidth().toFloat()
        val h = height.toFloat()
        if (isOpen) {
            val shadow = context.dpF(20f)
            shadowPaint.shader =
                LinearGradient(w, 0f, w + shadow, 0f, 0x73000000, 0, Shader.TileMode.CLAMP)
            canvas.drawRect(w, 0f, w + shadow, h, shadowPaint)
            sheetPaint.color = NeonColors.CARD
            canvas.drawRect(0f, 0f, w, h, sheetPaint)
            edgePaint.color = NeonColors.LINE2
            canvas.drawRect(w - context.dpF(1f), 0f, w, h, edgePaint)
        } else {
            sheetPaint.color = NeonColors.STAGE
            canvas.drawRect(0f, 0f, w, h, sheetPaint)
        }
    }

    companion object {
        private const val KEY_SEARCH = "search"
        private const val KEY_HOME = "home"
        private const val KEY_MORE = "more"
        private const val KEY_SETTINGS = "settings"
        private const val MORE_PREFIX = "more:"

        private fun itemKey(item: NavDrawerItem) = "item:" + item.id

        /** What the coach-mark tour calls a pinned menu item; libraries are spotlit together */
        private fun coachMarkId(item: NavDrawerItem): String? =
            when (item) {
                NavDrawerItem.Favorites -> CoachMarkIds.WATCHLIST
                NavDrawerItem.Discover -> CoachMarkIds.REQUESTS
                is ServerNavDrawerItem -> CoachMarkIds.LIBRARY_PREFIX + item.id
                else -> null
            }
    }
}

/**
 * The rail's list. On TV the focused row scrolls to about a third of the way down (Compose's TV bring-into-view
 * pivot), animated; while everything fits nothing scrolls.
 */
internal class RailScrollView(
    context: Context,
) : ScrollView(context) {
    private var suppress = false

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = false
        // Rows' glow may use the padding at both ends, but scrolled rows must not draw outside the list (over the
        // profile row or the brand), as the Compose LazyColumn clipped its content
        clipToOwnHeight()
        isSmoothScrollingEnabled = true
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    private fun maxScroll(): Int {
        val child = getChildAt(0) ?: return 0
        return max(0, child.height - (height - paddingTop - paddingBottom))
    }

    private fun pivotDelta(rect: Rect): Int {
        val max = maxScroll()
        if (max == 0) return 0
        val target = (height * .3f).roundToInt()
        val desired = (rect.top + paddingTop - target).coerceIn(0, max)
        return desired - scrollY
    }

    // ScrollView passes content coordinates (the list starts below the top padding)
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        if (suppress) 0 else pivotDelta(Rect(rect).apply { offset(0, -paddingTop) })

    override fun requestChildFocus(
        child: View?,
        focused: View?,
    ) {
        suppress = true
        super.requestChildFocus(child, focused)
        suppress = false
        if (focused != null && isLaidOut) {
            val r = Rect()
            focused.getDrawingRect(r)
            offsetDescendantRectToMyCoords(focused, r)
            r.offset(0, -paddingTop)
            val d = pivotDelta(r)
            if (d != 0) smoothScrollBy(0, d)
        }
    }
}

/**
 * One rail row (the old tv-material `NavigationDrawerItem` / `ListItem`): 16dp side padding, a 24dp glyph in a 32dp
 * slot, 8dp, then the label (and the server name under the profile's), then More's arrow.
 *
 * WeaselTV theme: transparent at rest; focused a `chipOn` fill with a 1dp accent border and accent glow, inset 10.5dp
 * from each side (`RailItemShape`); glyphs wear the section's glow colour, with a soft halo when selected; labels are
 * Exo 2 15 / 500, the accent when selected and not focused. Other themes: a rounded 8dp `inverseSurface` fill when
 * focused and the stock colours.
 */
@SuppressLint("ViewConstructor")
internal class RailRow(
    context: Context,
    private val twoLine: Boolean,
) : LinearLayout(context) {
    private val box = ShellBoxDrawable(context)
    private val leading = FrameLayout(context)
    private val icon = ImageView(context)
    private val glyph = TextView(context)
    private val avatar = AvatarView(context)
    private val badge = TextView(context)
    private val texts = LinearLayout(context)
    private val label = TextView(context)
    private val supporting = TextView(context)
    private val trailing = ImageView(context)

    private var accent = NeonColors.VOLT
    private var selected = false
    private var open = false
    private var openContainer = false
    private var profile = false
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false
        clipToPadding = false
        background = box
        setWillNotDraw(false)
        val pad = context.dp(16)
        setPadding(pad, 0, pad, 0)

        leading.clipChildren = false
        leading.clipToPadding = false
        leading.setPadding(context.dp(4), 0, context.dp(4), 0)
        val iconSize = context.dp(24)
        leading.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        glyph.gravity = Gravity.CENTER
        glyph.typeface = AppFonts.icons(context)
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        glyph.includeFontPadding = false
        leading.addView(glyph, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        leading.addView(avatar, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        // The request badge sits above the glyph, within the row, on both the open menu and the collapsed rail
        badge.gravity = Gravity.CENTER
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        badge.setTextColor(0xFF000000.toInt())
        badge.includeFontPadding = false
        badge.minWidth = context.dp(12)
        badge.setPadding(context.dp(3), 0, context.dp(3), 0)
        badge.background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dpF(6f)
                setColor(0xFF00F2D6.toInt())
            }
        leading.addView(badge, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(12), Gravity.TOP or Gravity.END))
        addView(leading, LayoutParams(context.dp(32), LayoutParams.MATCH_PARENT))

        texts.orientation = VERTICAL
        texts.gravity = Gravity.CENTER_VERTICAL
        label.maxLines = 1
        label.ellipsize = null
        supporting.maxLines = 1
        texts.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(supporting, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            texts,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(8) },
        )
        addView(
            trailing,
            LayoutParams(context.dp(24), context.dp(24)).apply { marginStart = context.dp(8) },
        )
    }

    private val neon get() = AppTheme.isNeon

    /** Search, Home or Settings: a drawable glyph */
    fun bindIcon(
        label: String,
        drawable: Int,
        accent: Int,
        selected: Boolean,
        open: Boolean,
    ) {
        profile = false
        this.accent = accent
        this.selected = selected
        this.open = open
        this.openContainer = false
        this.label.text = label
        icon.setImageResource(drawable)
        icon.visibility = VISIBLE
        glyph.visibility = GONE
        avatar.visibility = GONE
        badge.visibility = GONE
        supporting.visibility = GONE
        trailing.visibility = GONE
        refresh()
    }

    /** A pinned item, More, or one of More's items */
    fun bindItem(
        item: NavDrawerItem,
        selected: Boolean,
        open: Boolean,
        moreExpanded: Boolean,
        badge: Int,
        openContainer: Boolean = false,
    ) {
        profile = false
        accent = sectionAccent(item)
        this.selected = selected
        this.open = open
        this.openContainer = openContainer
        label.text = item.name(context)
        avatar.visibility = GONE
        supporting.visibility = GONE
        // WeaselFin ships its own drawable set; null on every upstream flavor, which falls through to the Font
        // Awesome glyph exactly as before
        val weasel = WeaselNavIcons.navIconFor(context, item)
        if (weasel != null) {
            icon.setImageResource(weasel)
            icon.visibility = VISIBLE
            glyph.visibility = GONE
        } else {
            glyph.text = context.getString(glyphFor(item))
            glyph.visibility = VISIBLE
            icon.visibility = GONE
        }
        if (badge > 0) {
            this.badge.text = if (badge > 9) "9+" else badge.toString()
            this.badge.visibility = VISIBLE
        } else {
            this.badge.visibility = GONE
        }
        if (item is NavDrawerItem.More) {
            trailing.setImageResource(
                if (moreExpanded) R.drawable.lb_shell_ic_arrow_drop_down else R.drawable.lb_shell_ic_keyboard_arrow_left,
            )
            trailing.visibility = VISIBLE
        } else {
            trailing.visibility = GONE
        }
        refresh()
    }

    /** The profile row: the user's picture, name and server */
    fun bindProfile(
        user: JellyfinUser,
        imageUrl: String?,
        serverName: String,
        open: Boolean,
    ) {
        profile = true
        accent = NeonColors.VOLT
        selected = false
        this.open = open
        openContainer = false
        icon.visibility = GONE
        glyph.visibility = GONE
        badge.visibility = GONE
        trailing.visibility = GONE
        avatar.visibility = VISIBLE
        // The avatar's colour is dimmed on the collapsed rail (other themes)
        avatar.bind(user.id, user.name, imageUrl, alpha = if (open || neon) 1f else .5f)
        label.text = user.name ?: user.id.toString()
        supporting.text = serverName
        supporting.visibility = VISIBLE
        refresh()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refresh()
    }

    private fun refresh() {
        val focused = isFocused
        // Expanded rows move their contents inside the stroke; collapsed icons and bounds stay put
        val inset = if (neon && open) context.dpF(8f) else 0f
        leading.translationX = inset
        texts.setPadding(inset.roundToInt(), 0, 0, 0)
        if (neon) {
            val a = ApprovedHomeLayout.CARD_APPEARANCE
            box.insetXPx = context.dpF(max(10f, a.glowSpreadDp.toFloat()) + a.borderWidthDp / 2f)
            box.set(
                fill = if (focused) NeonColors.chipOn(accent) else 0,
                border = if (focused) withAlpha(accent, a.borderOpacityPercent / 100f) else 0,
                borderDp = a.borderWidthDp.toFloat(),
                cornerDp = 0f,
                glow = if (focused) withAlpha(neonGlowAccent(accent), a.glowOpacityPercent / 100f) else 0,
                glowDp = a.glowSpreadDp.toFloat(),
            )
        } else {
            val p = AppTheme.palette
            box.insetXPx = 0f
            box.set(
                fill =
                    when {
                        focused -> p.inverseSurface
                        openContainer && open -> withAlpha(p.surface, .5f)
                        else -> 0
                    },
                cornerDp = 8f,
            )
        }
        val content = contentColor(focused)
        val glyphColor = if (neon) neonGlowAccent(accent) else navItemColor(focused)
        icon.imageTintList = ColorStateList.valueOf(glyphColor)
        glyph.setTextColor(glyphColor)
        trailing.imageTintList = ColorStateList.valueOf(content)
        if (profile) {
            if (neon) {
                ShellType.exo(label, 17f, 600, 1.25f, 0f, TextRole.TITLE_MEDIUM)
                label.setTextColor(0xFFF0F2F8.toInt())
                ShellType.exo(supporting, 13f, 400, 1.4f, 0f, TextRole.BODY_SMALL)
                supporting.setTextColor(0xFF828A9B.toInt())
            } else {
                label.applyTextRole(TextRole.TITLE_MEDIUM, weight = 600)
                label.setTextColor(content)
                supporting.applyTextRole(TextRole.BODY_SMALL, weight = 400)
                supporting.setTextColor(content)
            }
        } else if (neon) {
            ShellType.railLabel(label)
            // The active page remains visible when focus moves to a different sidebar button
            label.setTextColor(
                when {
                    selected && !focused -> accent
                    focused -> NeonColors.TEXT
                    else -> 0xFFE1E4EC.toInt()
                },
            )
        } else {
            label.applyTextRole(TextRole.TITLE_MEDIUM)
            label.setTextColor(content)
        }
        invalidate()
    }

    /** tv-material drawer item content colours (neonDrawerItemColors on the WeaselTV theme) */
    private fun contentColor(focused: Boolean): Int {
        if (neon) {
            return when {
                focused -> NeonColors.TEXT
                open -> NeonColors.LOW
                else -> withAlpha(NeonColors.LOW, .4f)
            }
        }
        val p = AppTheme.palette
        return when {
            focused -> p.inverseOnSurface
            open -> p.onSurfaceVariant
            else -> withAlpha(p.onSurfaceVariant, .4f)
        }
    }

    /** The old `navItemColor` for glyphs on themes other than WeaselTV */
    private fun navItemColor(focused: Boolean): Int {
        val p = AppTheme.palette
        if (AppTheme.theme == AppThemeColors.OLED_BLACK) {
            return when {
                selected && focused -> 0xFF000000.toInt()
                selected && !open -> withAlpha(0xFFFFFFFF.toInt(), .5f)
                selected && open -> withAlpha(0xFFFFFFFF.toInt(), .85f)
                focused -> withAlpha(0xFF000000.toInt(), .5f)
                open -> 0xFF707070.toInt()
                else -> withAlpha(0xFF505050.toInt(), .66f)
            }
        }
        val alpha =
            when {
                open -> .85f
                selected && !open -> .5f
                else -> .2f
            }
        val color =
            when {
                selected && focused -> if (AppTheme.theme == AppThemeColors.BOLD_BLUE) p.primary else p.border
                selected -> p.border
                focused -> contentColor(true)
                else -> p.onSurface
            }
        return withAlpha(color, alpha)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // The halo behind a selected glyph: a soft radial in the section's glow colour (neonIconGlow)
        if (neon && selected && !profile) {
            val cx = leading.left + leading.translationX + leading.width / 2f
            val cy = leading.top + leading.height / 2f
            val r = context.dpF(24f) * .95f
            val g = neonGlowAccent(accent)
            glowPaint.shader =
                RadialGradient(
                    cx,
                    cy,
                    r,
                    intArrayOf(withAlpha(g, .85f * .5f), withAlpha(g, .12f), 0),
                    floatArrayOf(0f, .5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawCircle(cx, cy, r, glowPaint)
        }
        super.dispatchDraw(canvas)
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)
        // The badge's place: the glyph box's top end, 2dp right and 12dp up
        if (badge.visibility == VISIBLE) {
            val iconRight = leading.paddingLeft + context.dp(24)
            badge.translationX = (iconRight - badge.right + context.dp(2)).toFloat()
            badge.translationY = (leading.height - context.dp(24)) / 2f - badge.top - context.dp(12)
        }
    }

    private fun glyphFor(item: NavDrawerItem): Int =
        when (item) {
            NavDrawerItem.Favorites -> {
                R.string.fa_heart
            }

            NavDrawerItem.More -> {
                R.string.fa_ellipsis
            }

            NavDrawerItem.Discover -> {
                R.string.fa_magnifying_glass_plus
            }

            is ServerNavDrawerItem -> {
                when (item.type) {
                    CollectionType.MOVIES -> R.string.fa_film
                    CollectionType.TVSHOWS -> R.string.fa_tv
                    CollectionType.HOMEVIDEOS -> R.string.fa_video
                    CollectionType.BOXSETS -> R.string.fa_open_folder
                    CollectionType.PLAYLISTS -> R.string.fa_list_ul
                    else -> R.string.fa_film
                }
            }
        }
}

/**
 * A user's picture (the old `UserIconCardImage`): square `card2` with a 1dp `line2` edge on the WeaselTV theme, a circle
 * in the user's colour elsewhere; the first letter when there is no picture.
 */
internal class AvatarView(
    context: Context,
) : FrameLayout(context) {
    private val image = ImageView(context)
    private val letter = TextView(context)
    private val bg = GradientDrawable()
    private var url: String? = null

    init {
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        letter.gravity = Gravity.CENTER
        letter.includeFontPadding = false
        addView(letter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        background = bg
        clipToOutline = true
    }

    fun bind(
        id: UUID,
        name: String?,
        imageUrl: String?,
        alpha: Float = 1f,
    ) {
        val neon = AppTheme.isNeon
        bg.shape = if (neon) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
        if (neon) {
            bg.setColor(NeonColors.CARD2)
            bg.setStroke(context.dp(1), NeonColors.LINE2)
        } else {
            bg.setColor(idColor(id, alpha))
            bg.setStroke(0, 0)
        }
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: Outline,
                ) {
                    if (neon) outline.setRect(0, 0, view.width, view.height) else outline.setOval(0, 0, view.width, view.height)
                }
            }
        letter.text = (name ?: id.toString()).firstOrNull()?.uppercase() ?: "?"
        ShellType.numeral(letter, 14f)
        letter.typeface = if (neon) AppFonts.body(context, 700) else AppFonts.body(context, 400)
        letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        letter.setTextColor(AppTheme.palette.onSurface)
        if (imageUrl != url) {
            url = imageUrl
            if (imageUrl.isNullOrBlank()) {
                image.setImageDrawable(null)
                image.visibility = GONE
                letter.visibility = VISIBLE
            } else {
                image.visibility = VISIBLE
                letter.visibility = GONE
                AvatarImages.load(image, id, imageUrl) {
                    image.visibility = GONE
                    letter.visibility = VISIBLE
                }
            }
        }
    }

    companion object {
        /** The old `rememberIdColor`: a darker colour from the id's hash */
        fun idColor(
            id: UUID,
            alpha: Float,
        ): Int {
            val hash = id.hashCode()
            val hue = (hash % 360).toFloat()
            val saturation = 0.6f + ((hash / 360) % 40).toFloat() / 100f
            val brightness = 0.4f + ((hash / 14400) % 30).toFloat() / 100f
            val c = brightness * saturation
            val x = c * (1 - abs((hue / 60f) % 2f - 1))
            val m = brightness - c
            val (r, g, b) =
                when {
                    hue < 60 -> Triple(c, x, 0f)
                    hue < 120 -> Triple(x, c, 0f)
                    hue < 180 -> Triple(0f, c, x)
                    hue < 240 -> Triple(0f, x, c)
                    hue < 300 -> Triple(x, 0f, c)
                    else -> Triple(c, 0f, x)
                }

            fun ch(v: Float) = ((v + m).coerceIn(0f, 1f) * 255).roundToInt()
            return ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}
