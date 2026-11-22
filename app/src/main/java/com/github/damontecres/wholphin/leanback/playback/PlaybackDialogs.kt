package com.github.damontecres.wholphin.leanback.playback

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.playback.SimpleMediaStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One choice of a bottom dialog (`BottomDialogItem`) */
internal data class BottomChoice<T>(
    val data: T,
    val headline: String,
    val supporting: String?,
    val enabled: Boolean = true,
)

/** A plain dialog window: no title, transparent, no dim (the video keeps playing behind it) */
internal fun playbackDialog(
    context: Context,
    gravity: Int,
    widthPx: Int,
): Dialog =
    Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(gravity)
            w.setDimAmount(0f)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

/** The width of a platform-default dialog in landscape (`windowMinWidthMajor`, 65 % of the screen) */
internal fun platformDialogWidth(context: Context): Int = (context.resources.displayMetrics.widthPixels * .65f).toInt()

/** Caps a child's height (a scrolling list inside a WRAP_CONTENT dialog) */
internal class MaxHeightScrollView(
    context: Context,
    var maxHeightPx: Int,
) : ScrollView(context) {
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (size in 1 until maxHeightPx) size else maxHeightPx
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/**
 * The bottom dialog panel: Neon Board `card` with a 1dp `line2` edge (square), a rounded surface elsewhere; 8dp from
 * the window edge; the list keeps the focused row's border and glow inside (16dp/20dp content padding on Neon).
 */
internal class BottomPanel(
    context: Context,
    elevationDp: Float,
) : FrameLayout(context) {
    val list = LinearLayout(context)
    val scroll: MaxHeightScrollView

    init {
        val neon = AppTheme.isNeon
        val m = context.dp(8)
        setPadding(m, m, m, m)
        clipChildren = false
        clipToPadding = false
        val panel = FrameLayout(context)
        panel.background =
            if (neon) {
                GradientDrawable().apply {
                    setColor(NeonColors.CARD)
                    setStroke(context.dp(1), NeonColors.LINE2)
                }
            } else {
                GradientDrawable().apply {
                    setColor(PlaybackColors.surfaceAtElevation(elevationDp))
                    cornerRadius = context.dpF(8f)
                }
            }
        scroll = MaxHeightScrollView(context, context.resources.displayMetrics.heightPixels - m * 4)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.clipChildren = false
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        list.clipToPadding = false
        if (neon) list.setPadding(context.dp(16), context.dp(20), context.dp(16), context.dp(20))
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** A 1dp divider (`HorizontalDivider`) */
    fun addDivider() {
        list.addView(
            View(context).apply { setBackgroundColor(if (AppTheme.isNeon) NeonColors.LINE else AppTheme.palette.borderVariant) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)),
        )
    }

    fun addItem(item: View) {
        list.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}

/**
 * `BottomDialog` / `StreamChoiceBottomDialog`: a list of choices at the bottom start or end, the current one marked
 * and focused. Choosing dismisses and reports the choice; BACK reports [onCancel].
 */
internal fun <T> showBottomDialog(
    context: Context,
    accent: Int,
    choices: List<BottomChoice<T>>,
    current: BottomChoice<T>?,
    gravity: Int,
    elevationDp: Float,
    onCancel: () -> Unit,
    onSelect: (Int, BottomChoice<T>) -> Unit,
): Dialog {
    val dialog = playbackDialog(context, Gravity.BOTTOM or gravity, platformDialogWidth(context))
    val panel = BottomPanel(context, elevationDp)
    var focus: View? = null
    choices.forEachIndexed { index, choice ->
        val item = PlaybackListItem(context, accent)
        item.bind(choice.headline, choice.supporting, selected = choice == current, enabled = choice.enabled)
        item.setOnClickListener {
            dialog.dismiss()
            onSelect(index, choice)
        }
        panel.addItem(item)
        if (choice == current) focus = item
    }
    dialog.setContentView(panel)
    dialog.setOnCancelListener { onCancel() }
    dialog.setOnShowListener { (focus ?: panel.list.getChildAt(0))?.requestFocus() }
    dialog.show()
    return dialog
}

/**
 * `SubtitleChoiceBottomDialog`: None, Only forced, the subtitle tracks, then "Search & download" (disabled without
 * the subtitle permission). UP on the first row jumps to the last and DOWN on the last to the first.
 */
internal fun showSubtitleDialog(
    context: Context,
    accent: Int,
    choices: List<SimpleMediaStream>,
    currentChoice: Int?,
    hasDownloadPermission: Boolean,
    onCancel: () -> Unit,
    onSelect: (Int) -> Unit,
    onSearch: () -> Unit,
): Dialog {
    val dialog = playbackDialog(context, Gravity.BOTTOM or Gravity.END, platformDialogWidth(context))
    val panel = BottomPanel(context, 3f)

    fun item(
        headline: String,
        supporting: String?,
        selected: Boolean,
        enabled: Boolean = true,
        showDot: Boolean = true,
        onClick: () -> Unit,
    ) = PlaybackListItem(context, accent, showLeading = true).apply {
        bind(headline, supporting, selected = selected, enabled = enabled)
        if (!showDot) setLeadingSlotVisible(false)
        setOnClickListener { onClick() }
    }

    val none =
        item(context.getString(R.string.none), null, currentChoice == TrackIndex.DISABLED) {
            dialog.dismiss()
            onSelect(TrackIndex.DISABLED)
        }
    panel.addItem(none)
    val forced =
        item(context.getString(R.string.only_forced_subtitles), null, currentChoice == TrackIndex.ONLY_FORCED) {
            dialog.dismiss()
            onSelect(TrackIndex.ONLY_FORCED)
        }
    panel.addItem(forced)
    if (choices.isNotEmpty()) panel.addDivider()
    var focus: View? = null
    choices.forEach { choice ->
        val v =
            item(
                choice.streamTitle ?: choice.displayTitle,
                choice.displayTitle.takeIf { choice.streamTitle != null },
                choice.index == currentChoice,
            ) {
                dialog.dismiss()
                onSelect(choice.index)
            }
        panel.addItem(v)
        if (choice.index == currentChoice) focus = v
    }
    panel.addDivider()
    val search =
        item(context.getString(R.string.search_and_download), null, false, enabled = hasDownloadPermission, showDot = false) {
            dialog.dismiss()
            onSearch()
        }
    panel.addItem(search)
    none.setOnKeyListener { _, _, event ->
        if (event.action == KeyEvent.ACTION_DOWN && PlaybackKeys.isUp(event)) {
            panel.scroll.fullScroll(View.FOCUS_DOWN)
            search.requestFocus()
            true
        } else {
            false
        }
    }
    search.setOnKeyListener { _, _, event ->
        if (event.action == KeyEvent.ACTION_DOWN && PlaybackKeys.isDown(event)) {
            panel.scroll.fullScroll(View.FOCUS_UP)
            none.requestFocus()
            true
        } else {
            false
        }
    }
    dialog.setContentView(panel)
    dialog.setOnCancelListener { onCancel() }
    dialog.setOnShowListener { (focus ?: none).requestFocus() }
    dialog.show()
    return dialog
}

/** The search row has an empty leading slot (`leadingContent = {}`), so it keeps the gap but no dot */
private fun PlaybackListItem.setLeadingSlotVisible(show: Boolean) {
    leading.visibility = if (show) View.VISIBLE else View.INVISIBLE
}

/**
 * The subtitle delay panel (`SubtitleDelay` in its dialog): "Subtitle delay: 1.5s" and -1s / -250ms / -50ms / Reset /
 * +50ms / +250ms / +1s buttons, Reset focused. Centred, no dim.
 */
internal class SubtitleDelayDialog(
    context: Context,
    private val accent: Int,
    private val onChangeDelay: (Duration) -> Unit,
    onCancel: () -> Unit,
) {
    private val label: TextView
    private var delay: Duration = Duration.ZERO
    val dialog: Dialog = playbackDialog(context, Gravity.CENTER, ViewGroup.LayoutParams.MATCH_PARENT)
    private val reset: SurfaceTextView

    init {
        val neon = AppTheme.isNeon
        val holder = FrameLayout(context)
        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background =
                    GradientDrawable().apply {
                        setColor(if (neon) NeonColors.CARD else PlaybackColors.BLACK_50)
                        if (!neon) cornerRadius = context.dpF(16f)
                    }
                val pad = context.dp(if (neon) 20 else 8)
                setPadding(pad, pad, pad, pad)
                clipChildren = false
                clipToPadding = false
            }
        label = context.textView(TextRole.BODY_LARGE)
        panel.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        val increments = listOf(50.milliseconds, 250.milliseconds, 1.seconds)

        fun button(
            text: String,
            onClick: () -> Unit,
        ): SurfaceTextView =
            OutlineButton
                .text(
                    context,
                    text,
                    accent,
                    paddingDp = intArrayOf(4, 4, 4, 4),
                    minHeightDp = 32,
                    corner = if (neon) 0f else context.dp(40) * .33f,
                ).apply {
                    PlaybackText.button(this)
                    setOnClickListener { onClick() }
                }
        val buttons = mutableListOf<SurfaceTextView>()
        increments.reversed().forEach { inc -> buttons.add(button("-$inc") { onChangeDelay(-inc) }) }
        reset = button(context.getString(R.string.reset)) { onChangeDelay(-delay) }
        buttons.add(reset)
        increments.forEach { inc -> buttons.add(button("+$inc") { onChangeDelay(inc) }) }
        buttons.forEachIndexed { i, b ->
            row.addView(
                b,
                LinearLayout.LayoutParams(context.dp(if (neon) 80 else 64), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = context.dp(8)
                },
            )
        }
        panel.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    context.dp(4)
            },
        )
        holder.addView(
            panel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        holder.clipChildren = false
        dialog.setContentView(holder)
        dialog.setOnCancelListener { onCancel() }
        dialog.setOnShowListener { reset.requestFocus() }
    }

    fun setDelay(value: Duration) {
        delay = value
        label.text = label.context.getString(R.string.subtitle_delay) + ": " + value.toString()
    }

    fun show() = dialog.show()
}

/** The left accent tally of the Neon dialog panel (`neonTally`): 3dp bar with a 12dp fading glow */
internal class TallyDrawable(
    private val context: Context,
    private val accent: Int,
) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = context.dpF(3f)
        val glowW = context.dpF(12f)
        paint.shader =
            LinearGradient(
                b.left.toFloat(),
                0f,
                b.left + glowW,
                0f,
                withAlpha(neonGlowAccent(accent), .45f),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + glowW, b.bottom.toFloat(), paint)
        paint.shader = null
        paint.color = accent
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + w, b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * `BasicDialogPanel`: Neon `card` with a 1dp `line2` edge and the accent tally (20dp shadow), a rounded
 * surfaceColorAtElevation panel elsewhere.
 */
internal fun basicDialogPanel(
    context: Context,
    accent: Int,
    elevationDp: Float = 1f,
): FrameLayout =
    FrameLayout(context).apply {
        if (AppTheme.isNeon) {
            background =
                android.graphics.drawable.LayerDrawable(
                    arrayOf(
                        GradientDrawable().apply {
                            setColor(NeonColors.CARD)
                            setStroke(context.dp(1), NeonColors.LINE2)
                        },
                        TallyDrawable(context, accent),
                    ),
                )
            elevation = context.dpF(20f)
        } else {
            background =
                GradientDrawable().apply {
                    setColor(PlaybackColors.surfaceAtElevation(elevationDp))
                    cornerRadius = context.dpF(8f)
                }
            elevation = context.dpF(elevationDp)
        }
    }
