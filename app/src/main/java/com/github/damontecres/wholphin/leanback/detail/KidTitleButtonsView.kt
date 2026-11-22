package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.ControlBorder
import com.github.damontecres.wholphin.leanback.theme.ControlBorders
import com.github.damontecres.wholphin.leanback.theme.ControlColors
import com.github.damontecres.wholphin.leanback.theme.ControlGlow
import com.github.damontecres.wholphin.leanback.theme.ControlGlows
import com.github.damontecres.wholphin.leanback.theme.ControlScales
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceStyle
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.SurfaceController
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailActionHeight
import com.github.damontecres.wholphin.ui.detail.KidAction
import com.github.damontecres.wholphin.ui.detail.KidPlay
import com.github.damontecres.wholphin.ui.detail.KidPlayLabel
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.formatEpisodeNumber
import com.github.damontecres.wholphin.ui.roundMinutes

/**
 * The kid title page's buttons (KidTitleButtons.kt, owner mockup 2026-10-23): the big pill Play button ([KidPlay];
 * none when there's nothing to play) with its progress or next-up line, then the round [KidAction] buttons, 22dp apart,
 * in the page's [accent].
 *
 * Focus starts on Play (else the first round button) and returns to the last focused button. A series' Play button
 * can arrive after the page shows (review round 2): it then takes the focus from the first round button, but only while
 * the focus is still in this row and no key was pressed since (review round 3). Every button calls [onButtonFocused]
 * (the page brings its header into view).
 */
class KidTitleButtonsView(
    context: Context,
    private val accent: Int,
    private val onPlay: (KidPlay) -> Unit,
    private val onAction: (KidAction) -> Unit,
    private val onButtonFocused: () -> Unit = {},
) : ActionButtonRow(context, spacingDp = 22f, glowRoomDp = 0f) {
    private var play: KidPlay? = null
    private var actions: List<KidAction> = emptyList()
    private var onWatchlist = false
    private var keyPressed = false

    private val playButton = KidPlayButton(context, accent) { play?.let(onPlay) }
    private val roundButtons = mutableMapOf<KidAction, KidRoundButton>()

    init {
        strip.gravity = Gravity.TOP
        // Room for the Play button's ring and glow
        strip.setPaddingRelative(context.dp(HeaderDimens.START_PADDING + 6f), context.dp(10), 0, context.dp(16))
        addButton(playButton)
        playButton.pill.setOnFocusChangeListener { _, hasFocus ->
            playButton.setRing(hasFocus)
            if (hasFocus) onButtonFocused()
        }
        reportDetailActionHeight()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) keyPressed = true
        return super.dispatchKeyEvent(event)
    }

    fun bind(
        play: KidPlay?,
        actions: List<KidAction>,
        onWatchlist: Boolean,
    ) {
        val hadPlay = this.play != null
        this.play = play
        this.onWatchlist = onWatchlist
        if (play != null) {
            playButton.bind(play)
            playButton.visibility = View.VISIBLE
        } else {
            if (playButton.pill.hasFocus()) actions.firstOrNull()?.let { roundButtons[it] }?.requestFocus()
            playButton.visibility = View.GONE
        }
        if (actions != this.actions) {
            this.actions = actions
            // Keep Play first; rebuild the round buttons in order
            roundButtons.values.forEach { strip.removeView(it.column) }
            val existing = roundButtons.toMap()
            roundButtons.clear()
            actions.forEach { action ->
                val button =
                    existing[action] ?: KidRoundButton(context, accent, action) { onAction(action) }.also { b ->
                        b.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onButtonFocused() }
                    }
                roundButtons[action] = button
                addButton(button.column)
            }
        }
        roundButtons.values.forEach { it.bind(onWatchlist) }
        firstFocus = if (play != null) playButton.pill else actions.firstOrNull()?.let { roundButtons[it] }
        // A late Play button takes the focus from the first round button while nothing was pressed since
        if (!hadPlay && play != null && hasFocus() && !keyPressed) {
            post { if (hasFocus() && !keyPressed) playButton.pill.requestFocus() }
        }
    }

    /** The view that takes the first focus (Play, else the first round button) */
    val firstFocusView: View? get() = firstFocus
}

/** `KidPlayButton`: a 66dp accent pill (Play icon 34dp, the label 21sp ExtraBold) in a ring, and its line below */
private class KidPlayButton(
    context: Context,
    private val accent: Int,
    onClick: () -> Unit,
) : LinearLayout(context) {
    private val ring =
        FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        }
    private val ringDrawable =
        GradientDrawable().apply {
            cornerRadius = context.dpF(100f)
            setStroke(context.dp(3), 0)
        }

    val pill =
        PillView(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            setPaddingRelative(context.dp(22), 0, context.dp(30), 0)
            showDividers = SHOW_DIVIDER_MIDDLE
            dividerDrawable = SpaceDrawable(context.dp(14))
        }
    private val surface: SurfaceController
    private val icon =
        ImageView(context).apply {
            setImageResource(R.drawable.lb_widget_ctl_ic_play_arrow)
            imageTintList = ColorStateList.valueOf(NeonColors.ON_ACCENT)
        }
    private val label =
        AppCompatTextView(context).apply {
            applyTextStyle(ControlTextStyle.of(TextRole.TITLE_LARGE, sizeSp = 21f, weight = 800))
            letterSpacing = .04f
            setTextColor(NeonColors.ON_ACCENT)
            maxLines = 1
            isSingleLine = true
        }
    private val progressRow =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            showDividers = SHOW_DIVIDER_MIDDLE
            dividerDrawable = SpaceDrawable(context.dp(10))
        }
    private val progressBar = KidProgressBar(context, accent)
    private val remaining = lineText(context)
    private val nextUp =
        lineText(context).apply {
            ellipsize = TextUtils.TruncateAt.END
        }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        showDividers = SHOW_DIVIDER_MIDDLE
        dividerDrawable = SpaceDrawable(context.dp(9))
        ring.background = ringDrawable
        pill.addView(icon, LayoutParams(context.dp(34), context.dp(34)))
        pill.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val glow = neonGlowAccent(accent)
        surface =
            SurfaceController(pill) {
                ControlSurfaceStyle(
                    container = ControlColors(accent),
                    content = ControlColors(NeonColors.ON_ACCENT),
                    glow =
                        ControlGlows(
                            rest = ControlGlow(16f, withAlpha(glow, .4f)),
                            focused = ControlGlow(22f, withAlpha(glow, .85f)),
                            pressed = ControlGlow(22f, withAlpha(glow, .85f)),
                        ),
                    scale = ControlScales.NONE,
                    cornerDp = ControlSurfaceStyle.CORNER_PILL,
                )
            }
        pill.surface = surface
        pill.setOnClickListener { onClick() }
        ring.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, context.dp(66)))
        addView(ring, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        progressRow.addView(progressBar, LayoutParams(context.dp(150), context.dp(6)))
        progressRow.addView(remaining, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(progressRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(14) })
        addView(nextUp, LayoutParams(context.dp(300), LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(14) })
        pill.addOnAttachStateChangeListener(
            object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = surface.onAttached()

                override fun onViewDetachedFromWindow(v: View) {}
            },
        )
    }

    /** Focused: a white ring 3dp outside the pill */
    fun setRing(focused: Boolean) {
        ringDrawable.setStroke(context.dp(3), if (focused) NeonColors.TEXT else 0)
        surface.refresh()
    }

    fun bind(play: KidPlay) {
        label.text =
            when (val l = play.label) {
                KidPlayLabel.Play -> context.getString(R.string.play)
                KidPlayLabel.KeepWatching -> context.getString(R.string.kid_keep_watching)
                is KidPlayLabel.Episode -> context.getString(R.string.kid_play_episode, l.season, formatEpisodeNumber(l.episode, l.episodeEnd))
            }.uppercase()
        val progress = play.progress
        val left = play.remaining
        if (progress != null && left != null) {
            progressRow.visibility = View.VISIBLE
            nextUp.visibility = View.GONE
            progressBar.progress = progress
            remaining.text = context.getString(R.string.time_left, context.resources.formatDuration(left.roundMinutes))
        } else if (play.episodeTitle != null) {
            progressRow.visibility = View.GONE
            nextUp.visibility = View.VISIBLE
            nextUp.text = context.getString(R.string.kid_next_up, play.episodeTitle)
        } else {
            progressRow.visibility = View.GONE
            nextUp.visibility = View.GONE
        }
    }

    companion object {
        /** bodyMedium at 14sp SemiBold, the Neon text colour at 90 % */
        fun lineText(context: Context): TextView =
            AppCompatTextView(context).apply {
                applyTextStyle(ControlTextStyle.of(TextRole.BODY_MEDIUM, sizeSp = 14f, weight = 600))
                setTextColor(withAlpha(NeonColors.TEXT, .9f))
                maxLines = 1
                isSingleLine = true
            }
    }
}

/** The focusable pill of [KidPlayButton]: refreshes its surface on focus / press changes */
private class PillView(
    context: Context,
) : LinearLayout(context) {
    var surface: SurfaceController? = null

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        surface?.refresh()
    }
}

/** The 150 × 6dp progress track (text at 25 %) with the accent fill, 3dp corners */
private class KidProgressBar(
    context: Context,
    private val accent: Int,
) : View(context) {
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(NeonColors.TEXT, .25f) }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val rect = android.graphics.RectF()

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = context.dpF(3f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        rect.set(0f, 0f, width * progress, height.toFloat())
        if (progress > 0f) canvas.drawRoundRect(rect, r, r, fill)
    }
}

/**
 * `KidRoundButton`: a 66dp circle (black at 80 %, a 2dp text outline at 85 %; focused: the accent chip fill, accent
 * outline and icon, the accent glow) with its uppercase label below (13sp Bold, 0.05em), 7dp apart.
 */
private class KidRoundButton(
    context: Context,
    private val accent: Int,
    private val action: KidAction,
    onClick: () -> Unit,
) : FrameLayout(context) {
    val column =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = SpaceDrawable(context.dp(7))
            // The ring row's top: Play's ring is 6dp outside its pill
            setPadding(0, context.dp(6), 0, 0)
        }
    private val surface: SurfaceController
    private val icon = ImageView(context)
    private val glyph =
        AppCompatTextView(context).apply {
            typeface = AppFonts.icons(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
    private val label =
        AppCompatTextView(context).apply {
            applyTextStyle(ControlTextStyle.of(TextRole.LABEL_MEDIUM, sizeSp = 13f, weight = 700))
            letterSpacing = .05f
            setTextColor(NeonColors.TEXT)
            maxLines = 1
            isSingleLine = true
        }
    private var onWatchlist = false

    init {
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        val glow = neonGlowAccent(accent)
        val chipOn = NeonColors.chipOn(accent)
        surface =
            SurfaceController(this) {
                ControlSurfaceStyle(
                    container = ControlColors(rest = withAlpha(0xFF000000.toInt(), .8f), focused = chipOn, pressed = chipOn),
                    content = ControlColors(rest = NeonColors.TEXT, focused = accent, pressed = accent),
                    border =
                        ControlBorders(
                            rest = ControlBorder(2f, withAlpha(NeonColors.TEXT, .85f)),
                            focused = ControlBorder(2f, accent),
                            pressed = ControlBorder(2f, accent),
                        ),
                    glow =
                        ControlGlows(
                            focused = ControlGlow(10f, withAlpha(glow, .85f)),
                            pressed = ControlGlow(10f, withAlpha(glow, .85f)),
                        ),
                    scale = ControlScales.NONE,
                    cornerDp = ControlSurfaceStyle.CORNER_PILL,
                )
            }
        surface.onContentColor = { color -> applyContent(color) }
        setOnClickListener { onClick() }
        when (action) {
            KidAction.START_OVER -> {
                icon.setImageResource(R.drawable.lb_widget_ctl_ic_refresh)
                icon.scaleX = -1f
                addView(icon, LayoutParams(context.dp(30), context.dp(30), Gravity.CENTER))
            }

            KidAction.WATCHLIST -> {
                addView(icon, LayoutParams(context.dp(30), context.dp(30), Gravity.CENTER))
            }

            KidAction.SHUFFLE -> {
                glyph.setText(R.string.fa_shuffle)
                addView(glyph, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
        }
        column.addView(this, LinearLayout.LayoutParams(context.dp(66), context.dp(66)))
        column.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    fun bind(onWatchlist: Boolean) {
        this.onWatchlist = onWatchlist
        label.text =
            when (action) {
                KidAction.START_OVER -> context.getString(R.string.kid_start_over)
                KidAction.WATCHLIST -> context.getString(if (onWatchlist) R.string.kid_on_watchlist else R.string.kid_watchlist)
                KidAction.SHUFFLE -> context.getString(R.string.shuffle)
            }.uppercase()
        if (action == KidAction.WATCHLIST) {
            icon.setImageResource(if (onWatchlist) R.drawable.lb_detail_ic_favorite else R.drawable.lb_detail_ic_favorite_border)
        }
        applyContent(surface.contentColor)
    }

    private fun applyContent(color: Int) {
        val iconColor = if (action == KidAction.WATCHLIST && onWatchlist) FAVORITE_RED else color
        icon.imageTintList = ColorStateList.valueOf(iconColor)
        glyph.setTextColor(color)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        @Suppress("SENSELESS_COMPARISON")
        if (surface != null) surface.refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surface.onAttached()
    }
}
