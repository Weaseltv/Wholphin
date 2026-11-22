package com.github.damontecres.wholphin.leanback.setup

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil3.load
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.setup.renderQrCode
import kotlin.math.max
import kotlin.math.min

/*
 * The kid-profile screens' shared look as Views (owner mockups, 2026-10-22; `ui/kids/KidPanel.kt`): a dark panel with a
 * 1dp edge and a glowing lime bar on its left, an Audiowide title, Exo 2 copy, key-hint chips, and square tiles / cards
 * that take a lime border, glow and dark lime fill when focused. Sizes in dp for the 960×540 dp TV frame.
 */

/**
 * The dark panel with the glowing lime bar on its left edge (`KidPanel`). [content] is the column the screen fills;
 * with [scrollable] it scrolls inside the panel. [maxHeightDp] caps the panel (516dp in a dialog).
 */
internal class KidPanelView(
    context: Context,
    scrollable: Boolean = true,
    private val maxHeightPx: Int = Int.MAX_VALUE,
    centered: Boolean = true,
) : FrameLayout(context) {
    val content: LinearLayout =
        vertical(context).apply {
            gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
            setPadding(context.dp(5 + 44), context.dp(34), context.dp(44), context.dp(34))
        }
    private val edgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(1f)
            color = KidPalette.PANEL_EDGE
        }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KidPalette.LIME }

    init {
        setWillNotDraw(false)
        clipChildren = true
        setBackgroundColor(KidPalette.PANEL)
        if (scrollable) {
            val scroll =
                ScrollView(context).apply {
                    isFillViewport = false
                    isVerticalScrollBarEnabled = false
                    clipChildren = false
                    clipToPadding = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            addView(scroll, frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            addView(content, frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
        val spec = if (cap == Int.MAX_VALUE) heightMeasureSpec else MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, spec)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val half = edgePaint.strokeWidth / 2
        canvas.drawRect(half, half, width - half, height - half, edgePaint)
        val bar = context.dpF(5f)
        GlowPainter.rect(canvas, 0f, 0f, bar, height.toFloat(), withAlpha(KidPalette.LIME, .7f), context.dpF(12f))
        canvas.drawRect(0f, 0f, bar, height.toFloat(), barPaint)
    }
}

/** The display-font panel title (`KidTitle`); long titles step down so the panel still fits */
internal fun Context.kidTitle(
    text: String,
    maxLines: Int = Int.MAX_VALUE,
): TextView = setupText(text, KidPalette.TEXT, Gravity.CENTER, maxLines) { kidDisplay(if (text.length > 22) 24f else 32f) }

/** Small, spaced, uppercase lime label above a dialog's content (`KidEyebrow`) */
internal fun Context.kidEyebrow(text: String): TextView =
    setupText(text.uppercase(), KidPalette.LIME, Gravity.CENTER) { kidDisplay(12f, trackingEm = .3f) }

internal fun Context.kidSubtitle(
    text: CharSequence,
    color: Int = KidPalette.MUTED,
    sizeSp: Float = 17f,
    maxLines: Int = Int.MAX_VALUE,
): TextView = setupText(text, color, Gravity.CENTER, maxLines) { kidBody(sizeSp) }

internal fun Context.kidErrorText(
    text: String,
    maxLines: Int = Int.MAX_VALUE,
): TextView = setupText(text, KidPalette.ERROR, Gravity.CENTER, maxLines) { kidBody(15f, 500) }

/** A section heading inside a panel, eg "What can they watch?" */
internal fun Context.kidSectionTitle(text: String): TextView = setupText(text, KidPalette.TEXT) { kidBody(19f, WEIGHT_BOLD) }

/** A label above a field, eg NAME */
internal fun Context.kidFieldLabel(text: String): TextView =
    setupText(text.uppercase(), KidPalette.MUTED) { kidBody(14f, WEIGHT_BOLD, .06f) }

/** A 1dp rule across the panel */
internal fun Context.kidDivider(): View =
    View(this).apply {
        setBackgroundColor(KidPalette.PANEL_EDGE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

/** One remote key and what it does, eg [OK] Select (`KeyHint`) */
internal fun Context.keyHint(
    key: String,
    label: String,
): LinearLayout =
    horizontal(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val chip =
            object : TextView(this@keyHint) {
                private val paint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = context.dpF(1f)
                        color = KidPalette.TILE_EDGE
                    }
                private val r = RectF()

                override fun onDraw(canvas: Canvas) {
                    val half = paint.strokeWidth / 2
                    r.set(half, half, width - half, height - half)
                    canvas.drawRoundRect(r, context.dpF(5f), context.dpF(5f), paint)
                    super.onDraw(canvas)
                }
            }.apply {
                kidBody(12f, WEIGHT_BOLD, .06f)
                text = key
                setTextColor(KidPalette.TEXT)
                includeFontPadding = false
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
        addView(chip)
        addView(hGap(8f))
        addView(setupText(label, KidPalette.MUTED) { kidBody(14f) })
    }

/** The footer key hints: [OK] [okLabel] and [BACK] Cancel (`OkBackHints`) */
internal fun Context.okBackHints(okLabel: String = getString(R.string.kid_hint_select)): LinearLayout =
    horizontal(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(keyHint(getString(R.string.kid_key_ok), okLabel))
        addView(hGap(28f))
        addView(keyHint(getString(R.string.kid_key_back), getString(R.string.cancel)))
    }

/**
 * An indeterminate ring spinner (`KidSpinner`: lime on the dark track; `CircularProgress`: the theme's `border`
 * colour with no track).
 */
internal class RingSpinner(
    context: Context,
    private val color: Int,
    private val trackColor: Int,
    strokeDp: Float,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(strokeDp)
            strokeCap = Paint.Cap.SQUARE
        }
    private val oval = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1333
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val half = paint.strokeWidth / 2
        oval.set(half, half, width - half, height - half)
        if ((trackColor ushr 24) != 0) {
            paint.color = trackColor
            canvas.drawOval(oval, paint)
        }
        paint.color = color
        // The arc grows and shrinks while it turns, like the Material indeterminate indicator
        val sweep = 30f + 240f * (if (phase < .5f) phase * 2 else (1 - phase) * 2)
        val start = 360f * phase * 2 + (if (phase < .5f) 0f else 240f * (phase - .5f) * 2)
        canvas.drawArc(oval, start - 90f, sweep, false, paint)
    }
}

/** The lime ring spinner (`KidSpinner`) */
internal fun Context.kidSpinner(
    sizeDp: Float = 84f,
    strokeDp: Float = 7f,
): View =
    RingSpinner(this, KidPalette.LIME, KidPalette.TRACK, strokeDp).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

/** A profile's picture, or the first letter of [name] when it has none or it fails to load (`KidAvatar`) */
internal class KidAvatarView(
    context: Context,
    letterSp: Float = 68f,
) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val letter =
        context.setupText(color = KidPalette.TEXT, gravity = Gravity.CENTER) { kidBody(letterSp, WEIGHT_BOLD) }

    init {
        addView(image, frame())
        addView(letter, frame(gravity = Gravity.CENTER))
    }

    fun bind(
        name: String,
        imageUrl: String?,
    ) {
        letter.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        image.contentDescription = name
        if (imageUrl.isNullOrBlank()) {
            image.visibility = View.GONE
            letter.visibility = View.VISIBLE
        } else {
            image.visibility = View.VISIBLE
            letter.visibility = View.GONE
            image.load(imageUrl) {
                listener(
                    onError = { _, _ ->
                        image.visibility = View.GONE
                        letter.visibility = View.VISIBLE
                    },
                )
            }
        }
    }
}

/** The kid tile surface: lime border, glow and dark fill when focused (`KidTile`) */
internal fun kidTileStyle(dashed: Boolean): SurfaceStyle =
    SurfaceStyle(
        fill = KidPalette.TILE,
        focusedFill = KidPalette.LIME_FILL,
        disabledFill = withAlpha(KidPalette.TILE, .5f),
        selectedFill = KidPalette.LIME_FILL,
        borderWidthDp = if (dashed) 0f else 1.5f,
        borderColor = KidPalette.TILE_EDGE,
        focusedBorderWidthDp = 2.5f,
        focusedBorderColor = KidPalette.LIME,
        disabledBorderWidthDp = 1f,
        disabledBorderColor = withAlpha(KidPalette.TILE_EDGE, .5f),
        selectedBorderWidthDp = 2f,
        selectedBorderColor = withAlpha(KidPalette.LIME, .75f),
        dashedRestColor = if (dashed) KidPalette.TILE_EDGE else null,
        glowColor = withAlpha(KidPalette.LIME, .55f),
        glowRadiusDp = 14f,
        focusedScale = 1.06f,
    )

/**
 * A large square tile with [label] under it (a kid, an avatar, "Add a new kid"). Focused: lime border, glow and dark
 * lime fill, and the label turns lime. [surface] is the focusable tile; put its content in it.
 */
internal class KidTileView(
    context: Context,
    label: String?,
    sizeDp: Float = 150f,
    dashed: Boolean = false,
    caption: String? = null,
    description: String? = label,
) : LinearLayout(context) {
    val surface = SetupSurface(context, kidTileStyle(dashed))
    private val labelView: TextView?

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        surface.contentDescription = description
        addView(surface, LayoutParams(context.dp(sizeDp), context.dp(sizeDp)))
        labelView =
            label?.let {
                context.setupText(it, KidPalette.TEXT, Gravity.CENTER, 1) { kidBody(18f, WEIGHT_BOLD) }.also { tv ->
                    addView(
                        tv,
                        LayoutParams(context.dp(sizeDp + 24), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin =
                                context.dp(14)
                        },
                    )
                }
            }
        caption?.let {
            addView(
                context.setupText(it, KidPalette.FAINT, Gravity.CENTER) { kidBody(13f) },
                LayoutParams(context.dp(sizeDp + 24), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(14) },
            )
        }
        surface.onFocusVisual = { recolor(it) }
        recolor(false)
    }

    private fun recolor(focused: Boolean) {
        labelView?.setTextColor(
            when {
                focused -> KidPalette.LIME
                surface.isEnabled -> KidPalette.TEXT
                else -> KidPalette.FAINT
            },
        )
    }

    fun setTileEnabled(enabled: Boolean) {
        surface.isEnabled = enabled
        recolor(surface.isFocused)
    }
}

/** The thin "+" of an "add" tile (`KidPlus`) */
internal class KidPlusView(
    context: Context,
    private val color: Int = KidPalette.MUTED,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = context.dpF(2.5f)
            this.color = this@KidPlusView.color
        }

    override fun onDraw(canvas: Canvas) {
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
    }
}

/** An outlined button; focused: lime border, glow and fill; [arrow] adds a lime → after the label (`KidButton`) */
internal class KidButtonView(
    context: Context,
    text: String,
    arrow: Boolean = false,
    minWidthDp: Float = 200f,
) : SetupSurface(
        context,
        SurfaceStyle(
            fill = KidPalette.TILE,
            focusedFill = KidPalette.LIME_FILL,
            disabledFill = KidPalette.TILE,
            borderWidthDp = 2f,
            borderColor = withAlpha(KidPalette.MUTED, .7f),
            focusedBorderWidthDp = 2.5f,
            focusedBorderColor = KidPalette.LIME,
            disabledBorderWidthDp = 1f,
            disabledBorderColor = KidPalette.TILE_EDGE,
            glowColor = withAlpha(KidPalette.LIME, .5f),
            glowRadiusDp = 12f,
        ),
    ) {
    private val label = context.setupText(text, KidPalette.TEXT, maxLines = 1) { kidBody(19f, WEIGHT_BOLD, .02f) }

    init {
        minimumWidth = context.dp(minWidthDp)
        contentDescription = text
        val row =
            horizontal(context).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(24), 0, context.dp(24), 0)
                minimumWidth = context.dp(minWidthDp)
                addView(label)
                if (arrow) {
                    addView(context.hGap(14f))
                    addView(
                        TextView(context).apply {
                            faGlyph(16f)
                            setText(R.string.fa_arrow_right_long)
                            setTextColor(KidPalette.LIME)
                        },
                    )
                }
            }
        addView(row, frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        onFocusVisual = { recolor() }
        recolor()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        recolor()
    }

    private fun recolor() {
        label.setTextColor(
            when {
                !isEnabled -> KidPalette.FAINT
                isFocused -> KidPalette.LIME
                else -> KidPalette.TEXT
            },
        )
    }

    companion object {
        /** Layout params at the button's 50dp height */
        fun params(context: Context): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(50))
    }
}

/** A KidButton with its 50dp height */
internal fun Context.kidButton(
    text: String,
    arrow: Boolean = false,
    minWidthDp: Float = 200f,
    onClick: () -> Unit,
): KidButtonView =
    KidButtonView(this, text, arrow, minWidthDp).apply {
        layoutParams = KidButtonView.params(this@kidButton)
        setOnClickListener { onClick() }
    }

/** A full-width choice row with an optional Font Awesome [glyph] (`KidListRow`) */
internal class KidListRowView(
    context: Context,
    text: String,
    glyph: String? = null,
) : SetupSurface(
        context,
        SurfaceStyle(
            fill = KidPalette.TILE,
            focusedFill = KidPalette.LIME_FILL,
            borderWidthDp = 1.5f,
            borderColor = KidPalette.TILE_EDGE,
            focusedBorderWidthDp = 2.5f,
            focusedBorderColor = KidPalette.LIME,
            glowColor = withAlpha(KidPalette.LIME, .5f),
            glowRadiusDp = 12f,
        ),
    ) {
    private val glyphView: TextView? =
        glyph?.let {
            TextView(context).apply {
                faGlyph(16f)
                this.text = it
                gravity = Gravity.CENTER
            }
        }
    private val label = context.setupText(text, KidPalette.TEXT) { kidBody(18f, 600) }

    init {
        minimumHeight = context.dp(56)
        contentDescription = text
        val row =
            horizontal(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(14))
                glyphView?.let {
                    addView(it, LinearLayout.LayoutParams(context.dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(context.hGap(16f))
                }
                addView(label)
            }
        addView(row, frame(height = ViewGroup.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_VERTICAL))
        onFocusVisual = { recolor(it) }
        recolor(false)
    }

    private fun recolor(focused: Boolean) {
        glyphView?.setTextColor(if (focused) KidPalette.LIME else KidPalette.MUTED)
        label.setTextColor(if (focused) KidPalette.LIME else KidPalette.TEXT)
    }
}

internal fun Context.kidListRow(
    text: String,
    glyph: String? = null,
    onClick: () -> Unit,
): KidListRowView =
    KidListRowView(this, text, glyph).apply {
        layoutParams = matchWidth()
        setOnClickListener { onClick() }
    }

/** A level card: title, description and a radio circle; selected: lime border, dark lime fill, filled radio */
internal class KidOptionCardView(
    context: Context,
    title: String,
    description: String,
) : SetupSurface(
        context,
        SurfaceStyle(
            fill = KidPalette.TILE,
            focusedFill = KidPalette.LIME_FILL,
            selectedFill = KidPalette.LIME_FILL,
            borderWidthDp = 1.5f,
            borderColor = KidPalette.TILE_EDGE,
            selectedBorderWidthDp = 2f,
            selectedBorderColor = withAlpha(KidPalette.LIME, .75f),
            focusedBorderWidthDp = 2.5f,
            focusedBorderColor = KidPalette.LIME,
            glowColor = withAlpha(KidPalette.LIME, .5f),
            glowRadiusDp = 12f,
            focusedScale = 1.02f,
        ),
    ) {
    private val radio = RadioDot(context)

    init {
        minimumHeight = context.dp(100)
        contentDescription = title
        val column =
            vertical(context).apply {
                setPadding(context.dp(18), context.dp(16), context.dp(18), context.dp(16))
                val top =
                    horizontal(context).apply {
                        gravity = Gravity.TOP
                        addView(
                            context
                                .setupText(
                                    title,
                                    KidPalette.TEXT,
                                ) { kidBody(21f, WEIGHT_BOLD) }
                                .apply { setPadding(0, context.dp(4), 0, 0) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(radio, LinearLayout.LayoutParams(context.dp(23), context.dp(23)))
                    }
                addView(top, matchWidth())
                addView(context.gap(8f))
                addView(context.setupText(description, KidPalette.MUTED) { kidBody(15f) }, matchWidth())
            }
        addView(column, frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setSelectedOption(selected: Boolean) {
        selectedState = selected
        radio.checked = selected
    }

    private class RadioDot(
        context: Context,
    ) : View(context) {
        var checked = false
            set(value) {
                field = value
                invalidate()
            }
        private val ring =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = context.dpF(2f)
            }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KidPalette.LIME }

        override fun onDraw(canvas: Canvas) {
            ring.color = if (checked) KidPalette.LIME else KidPalette.MUTED
            val r = min(width, height) / 2f
            canvas.drawCircle(width / 2f, height / 2f, r - ring.strokeWidth / 2, ring)
            if (checked) canvas.drawCircle(width / 2f, height / 2f, context.dpF(13f) / 2, dot)
        }
    }
}

/** The name field: lime border, glow and caret when focused, [placeholder] while empty (`KidTextField`) */
internal class KidTextFieldView(
    context: Context,
    placeholder: String,
) : EditText(context) {
    var isError: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        background = null
        kidBody(22f)
        setTextColor(KidPalette.TEXT)
        setHintTextColor(KidPalette.FAINT)
        hint = placeholder
        isSingleLine = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(context.dp(18), 0, context.dp(18), 0)
        minHeight = context.dp(54)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(KidPalette.LIME)
                    setSize(context.dp(2), context.dp(26))
                }
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(54))
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val focused = isFocused
        val sx = scrollX.toFloat()
        val l = sx
        val r = sx + width
        if (focused) GlowPainter.rect(canvas, l, 0f, r, height.toFloat(), withAlpha(KidPalette.LIME, .5f), context.dpF(14f))
        fill.color = if (focused) KidPalette.LIME_FILL else KidPalette.TILE
        canvas.drawRect(l, 0f, r, height.toFloat(), fill)
        super.draw(canvas)
        val w = context.dpF(if (focused) 2.5f else 1.5f)
        border.strokeWidth = w
        border.color =
            when {
                focused -> KidPalette.LIME
                isError -> KidPalette.ERROR
                else -> KidPalette.TILE_EDGE
            }
        canvas.drawRect(l + w / 2, w / 2, r - w / 2, height - w / 2, border)
    }

    companion object {
        /** The name keyboard: words capitalised, no autocorrect, Done */
        const val NAME_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        const val IME_DONE = EditorInfo.IME_ACTION_DONE
    }
}

/** "Kid" on a kid profile's tile (`KidBadge`): a lime chip with dark Exo 2 lettering and a soft glow */
internal class KidBadgeView(
    context: Context,
) : TextView(context) {
    init {
        kidBody(11f, WEIGHT_BOLD, .12f)
        text = context.getString(R.string.kid_badge).uppercase()
        setTextColor(NeonColors.ON_ACCENT)
        includeFontPadding = false
        setPadding(context.dp(7), context.dp(2), context.dp(7), context.dp(2))
    }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KidPalette.LIME }

    override fun onDraw(canvas: Canvas) {
        GlowPainter.rect(canvas, 0f, 0f, width.toFloat(), height.toFloat(), withAlpha(KidPalette.LIME, .5f), context.dpF(8f))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        super.onDraw(canvas)
    }
}

/** [length] boxes: filled ones show a dot, the next one a lime border, glow and caret (`PinBoxes`) */
internal class PinBoxesView(
    context: Context,
    private val length: Int,
) : View(context) {
    var filled: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    // Four boxes as drawn; a longer PIN saved before the 4-press rule gets narrower boxes so they still fit
    private val boxW = if (length <= 5) context.dpF(75f) else max(context.dpF(440f) / length, context.dpF(28f))
    private val gapW = if (length <= 5) context.dpF(18f) else context.dpF(8f)
    private val boxH = context.dpF(92f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension((boxW * length + gapW * (length - 1)).toInt(), boxH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        for (index in 0 until length) {
            val left = index * (boxW + gapW)
            val right = left + boxW
            val current = index == filled
            if (current) GlowPainter.rect(canvas, left, 0f, right, boxH, withAlpha(KidPalette.LIME, .6f), context.dpF(14f))
            paint.color = if (current) KidPalette.LIME_FILL else KidPalette.TILE
            canvas.drawRect(left, 0f, right, boxH, paint)
            val sw = context.dpF(if (current) 2.5f else 1.5f)
            stroke.strokeWidth = sw
            stroke.color = if (current) KidPalette.LIME else KidPalette.TILE_EDGE
            canvas.drawRect(left + sw / 2, sw / 2, right - sw / 2, boxH - sw / 2, stroke)
            val cx = (left + right) / 2
            when {
                index < filled -> {
                    paint.color = KidPalette.TEXT
                    canvas.drawCircle(cx, boxH / 2, context.dpF(17f) / 2, paint)
                }

                current -> {
                    paint.color = KidPalette.LIME
                    val cw = context.dpF(3f) / 2
                    val ch = context.dpF(40f) / 2
                    canvas.drawRect(cx - cw, boxH / 2 - ch, cx + cw, boxH / 2 + ch, paint)
                }
            }
        }
    }
}

/** The remote's D-pad as a hint: ↑ / ← OK → / ↓ (`DpadHint`) */
internal fun Context.dpadHint(): LinearLayout {
    fun key(
        text: String,
        glyph: Boolean,
    ): View =
        object : TextView(this) {
            private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KidPalette.TILE }
            private val edge =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = context.dpF(1f)
                    color = KidPalette.TILE_EDGE
                }
            private val r = RectF()

            override fun onDraw(canvas: Canvas) {
                val radius = context.dpF(8f)
                r.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(r, radius, radius, bg)
                val half = edge.strokeWidth / 2
                r.inset(half, half)
                canvas.drawRoundRect(r, radius, radius, edge)
                super.onDraw(canvas)
            }
        }.apply {
            if (glyph) faGlyph(15f) else kidBody(14f, WEIGHT_BOLD)
            this.text = text
            setTextColor(KidPalette.TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }

    fun spaced(
        view: View,
        startDp: Float = 0f,
        topDp: Float = 0f,
    ): View =
        view.apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(startDp)
                topMargin = dp(topDp)
            }
        }
    return vertical(this).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(key(getString(R.string.fa_arrow_up_long), true))
        addView(
            horizontal(this@dpadHint).apply {
                addView(key(getString(R.string.fa_arrow_left_long), true))
                addView(spaced(key(getString(R.string.kid_key_ok), false), startDp = 4f))
                addView(spaced(key(getString(R.string.fa_arrow_right_long), true), startDp = 4f))
            },
            wrap(Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) },
        )
        addView(spaced(key(getString(R.string.fa_arrow_down_long), true), topDp = 4f))
    }
}

/** A QR code for [content], black on white so a phone camera reads it off a TV screen (`QrCode`) */
internal fun Context.qrCode(
    content: String,
    sizeDp: Float = 200f,
): ImageView =
    ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(renderQrCode(content, QR_PIXELS))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

private const val QR_PIXELS = 512

/** "Forgot your PIN? You can reset it on the website at …", beside a QR code for [url] (`ForgotPinHint`) */
internal fun Context.forgotPinHint(url: String): LinearLayout {
    val shown = url.removePrefix("https://").removePrefix("http://")
    return horizontal(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(qrCode(url, 112f))
        addView(hGap(18f))
        addView(
            setupText(emphasise(getString(R.string.forgot_pin_hint, shown), shown, KidPalette.LIME), KidPalette.TEXT) { kidBody(16f) },
            LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
}

/** Sets a text size in sp (for views that take a role and then a size) */
internal fun TextView.sizeSp(sp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
