package com.github.damontecres.wholphin.leanback.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.Avatar

/**
 * The avatar grid (plan 4.6; `ProfilePicturePicker`) in the kid-profile panel look: [title] and [subtitle] above a
 * D-pad grid of square tiles (lime border, glow and fill when focused), the current picture marked with a lime edge and
 * check.
 *
 * Owner mockup (2026-10-22): the grid shows three full rows (the rest scroll), and the bottom row holds the OK / BACK
 * hints on the left with the buttons on the right: [onRemove] adds "Remove picture", [onSkip] "Skip for now". [footer]
 * (progress, errors) sits between them, so it never pushes the grid off the panel.
 *
 * Focus opens on the current avatar, scrolled into view; a current avatar found later moves the focus to it only until
 * the user has moved (any key in the picker, or focus on any other tile, counts).
 */
@SuppressLint("ViewConstructor")
internal class AvatarPickerView(
    context: Context,
    title: String,
    subtitle: String?,
    onPick: (Avatar) -> Unit,
    onRemove: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) : LinearLayout(context) {
    private val adapter = AvatarAdapter(onPick) { index -> onTileFocused(index) }
    private val grid =
        RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            this.adapter = this@AvatarPickerView.adapter
            itemAnimator = null
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            // Room for the focused tile's scale and glow: half the gap around every tile plus the rest at the edges
            val edge = context.dp(GRID_PADDING_DP - TILE_GAP_DP / 2)
            setPadding(edge, edge, edge, edge)
        }

    /** Progress or errors between the hints and the buttons */
    val footer = FrameLayout(context)
    private val removeButton = onRemove?.let { context.kidButton(context.getString(R.string.remove_picture)) { it() } }
    private val skipButton = onSkip?.let { context.kidButton(context.getString(R.string.skip_for_now), arrow = true) { it() } }

    private var avatars: List<Avatar> = emptyList()
    private var current: String? = null
    private var focusedByPicker = -1
    private var settled = false
    private var userMoved = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(context.kidTitle(title), wrap(Gravity.CENTER_HORIZONTAL))
        if (!subtitle.isNullOrBlank()) {
            addView(context.gap(10f))
            addView(context.kidSubtitle(subtitle), wrap(Gravity.CENTER_HORIZONTAL))
        }
        addView(context.gap(10f))
        val gridWidth = context.dp(TILE_DP * COLUMNS + TILE_GAP_DP * (COLUMNS - 1) + GRID_PADDING_DP * 2)
        val gridHeight = context.dp(TILE_DP * VISIBLE_ROWS + TILE_GAP_DP * (VISIBLE_ROWS - 1) + GRID_PADDING_DP * 2)
        addView(grid, LayoutParams(gridWidth, gridHeight).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(context.gap(12f))
        val bottom =
            horizontal(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(50)
                addView(context.okBackHints())
                addView(
                    footer,
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = context.dp(16)
                        marginEnd = context.dp(16)
                    },
                )
                removeButton?.let { addView(it) }
                if (removeButton != null && skipButton != null) addView(context.hGap(16f))
                skipButton?.let { addView(it) }
            }
        addView(bottom, matchWidth())
    }

    /** Shows [avatars] with [current] marked; [enabled] false while a pick is saved */
    fun bind(
        avatars: List<Avatar>,
        current: String?,
        enabled: Boolean,
    ) {
        val listChanged = avatars != this.avatars
        val currentChanged = current != this.current
        this.avatars = avatars
        this.current = current
        if (listChanged) {
            focusedByPicker = -1
            settled = false
            userMoved = false
        }
        adapter.update(avatars, current, enabled)
        removeButton?.isEnabled = enabled
        skipButton?.isEnabled = enabled
        if ((listChanged || currentChanged) && avatars.isNotEmpty() && !userMoved) focusInitial()
    }

    private fun focusInitial() {
        // The saved picture may be past the first rows: bring it into view, wait for that layout, then focus it
        val index = avatars.indexOfFirst { it.id == current }.coerceAtLeast(0)
        focusedByPicker = index
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(index, 0)
        grid.doOnNextLayout {
            // The user may have moved while it scrolled or waited for the layout: then focus stays put
            if (!userMoved) grid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            settled = true
        }
    }

    private fun onTileFocused(index: Int) {
        if (settled && index != focusedByPicker) userMoved = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (settled && event.action == KeyEvent.ACTION_DOWN) userMoved = true
        return super.dispatchKeyEvent(event)
    }

    private class AvatarAdapter(
        private val onPick: (Avatar) -> Unit,
        private val onFocused: (Int) -> Unit,
    ) : RecyclerView.Adapter<AvatarHolder>() {
        private var items: List<Avatar> = emptyList()
        private var current: String? = null
        private var enabled = true

        @SuppressLint("NotifyDataSetChanged")
        fun update(
            items: List<Avatar>,
            current: String?,
            enabled: Boolean,
        ) {
            val listChanged = items != this.items
            val oldCurrent = this.current
            val enabledChanged = enabled != this.enabled
            this.items = items
            this.current = current
            this.enabled = enabled
            if (listChanged || enabledChanged) {
                notifyDataSetChanged()
            } else if (oldCurrent != current) {
                items.indexOfFirst { it.id == oldCurrent }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
                items.indexOfFirst { it.id == current }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): AvatarHolder = AvatarHolder(parent.context)

        override fun onBindViewHolder(
            holder: AvatarHolder,
            position: Int,
        ) {
            val avatar = items[position]
            holder.bind(avatar, avatar.id == current, enabled)
            holder.tile.setOnClickListener { if (enabled) onPick(avatar) }
            holder.tile.setOnFocusChangeListener { _, has -> if (has) onFocused(holder.bindingAdapterPosition) }
        }
    }

    private class AvatarHolder(
        context: Context,
    ) : RecyclerView.ViewHolder(
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                val half = context.dp(TILE_GAP_DP / 2)
                setPadding(half, half, half, half)
            },
        ) {
        val tile = SetupSurface(context, kidTileStyle(dashed = false))
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val check =
            object : FrameLayout(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KidPalette.LIME }

                init {
                    setWillNotDraw(false)
                    contentDescription = context.getString(R.string.current_picture)
                }

                override fun onDraw(canvas: Canvas) {
                    canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
                }
            }.apply {
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.lb_setup_ic_check)
                        imageTintList = ColorStateList.valueOf(NeonColors.ON_ACCENT)
                    },
                    frame(context.dp(16), context.dp(16), Gravity.CENTER),
                )
            }

        init {
            tile.addView(image, frame())
            tile.addView(
                check,
                frame(context.dp(22), context.dp(22), Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, context.dp(4), context.dp(4))
                },
            )
            (itemView as FrameLayout).addView(tile, frame(context.dp(TILE_DP), context.dp(TILE_DP)))
        }

        fun bind(
            avatar: Avatar,
            selected: Boolean,
            enabled: Boolean,
        ) {
            tile.contentDescription = avatar.id
            tile.selectedState = selected
            // Disabled while a pick saves: it looks disabled and ignores OK, but keeps the focus where the user is
            tile.isEnabled = enabled
            tile.isFocusable = true
            val pad = if (selected) tile.context.dp(2) else 0
            (image.layoutParams as FrameLayout.LayoutParams).setMargins(pad, pad, pad, pad)
            image.requestLayout()
            image.load(avatar.model)
            image.contentDescription = avatar.id
            check.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    companion object {
        /** Grid columns; 48 kid and 60 adult avatars make eight and ten rows, of which three show */
        private const val COLUMNS = 6
        private const val VISIBLE_ROWS = 3
        private const val TILE_DP = 76f
        private const val TILE_GAP_DP = 14f
        private const val GRID_PADDING_DP = 12f
    }
}
