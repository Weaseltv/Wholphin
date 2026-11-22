package com.github.damontecres.wholphin.leanback.setup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.compositeOver
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.services.NavTiming
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.theme.LoadingArtworkPreload
import com.github.damontecres.wholphin.ui.theme.LoadingArtworkRotation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.ln

/*
 * The plain page controls the setup screens used (no-Compose UI): the brand row, page title rule and eyebrow, the outline
 * `Button`/`TextButton`, `EditTextBox`, `CircularProgress`, `LoadingPage`, `ErrorMessage` and the dialog list row. Private
 * versions for the SETUP area until the shared CONTROLS widgets land.
 */

/** Mascot + wordmark (`NeonBrandRow`): the colour lockup when the flavor ships it, else the mascot and text wordmark */
internal fun Context.neonBrandRow(
    mascotDp: Float = 40f,
    wordmarkSp: Float = 34f,
): View {
    val lockup = resources.getIdentifier("weaselplex_lockup", "drawable", packageName)
    if (lockup != 0) {
        return ImageView(this).apply {
            setImageResource(lockup)
            adjustViewBounds = true
            // The lockup is mascotDp * 1.6 tall (`Modifier.height`). maxHeight keeps that when the caller adds the view
            // with its own wrap_content params, which replace these and drew the lockup at its full 210dp height
            maxHeight = dp(mascotDp * 1.6f)
            contentDescription = "WeaselPlex"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(mascotDp * 1.6f))
        }
    }
    return horizontal(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val mascot = resources.getIdentifier("weaselplex_mascot", "drawable", packageName)
        if (mascot != 0) {
            addView(ImageView(this@neonBrandRow).apply { setImageResource(mascot) }, LinearLayout.LayoutParams(dp(mascotDp), dp(mascotDp)))
            addView(hGap(12f))
        }
        val text =
            SpannableStringBuilder("WEASELPLEX").apply {
                setSpan(ForegroundColorSpan(NeonColors.TEXT), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(NeonColors.VOLT), 6, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        addView(
            setupText(text, maxLines = 1) {
                if (AppTheme.isNeon) {
                    neonHeader(wordmarkSp, TextRole.TITLE_LARGE)
                } else {
                    applyTextRole(TextRole.TITLE_LARGE, sizeSp = wordmarkSp, weight = 800)
                }
            },
        )
    }
}

/**
 * The section rule under a page title (`NeonRule`): a 3dp accent line with its glow rising above it. Only on the WeaselTV
 * theme; elsewhere it is GONE.
 */
internal class NeonRuleView(
    context: Context,
    private val accent: Int = NeonColors.VOLT,
) : View(context) {
    private val line = Paint().apply { color = accent }
    private val glow = Paint()
    private val thickness = context.dpF(ApprovedHomeLayout.DIVIDER_THICKNESS_DP.toFloat())
    private val glowHeight = context.dpF(ApprovedHomeLayout.DIVIDER_GLOW_DP.toFloat())

    init {
        visibility = if (AppTheme.isNeon) View.VISIBLE else View.GONE
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), thickness.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val strength = (ApprovedHomeLayout.DIVIDER_GLOW_STRENGTH / 100f).coerceIn(0f, 1f)
        glow.shader =
            LinearGradient(
                0f,
                h - glowHeight,
                0f,
                h,
                0,
                withAlpha(NeonSections.glowFor(accent), strength),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, h - glowHeight, width.toFloat(), h, glow)
        canvas.drawRect(0f, h - thickness, width.toFloat(), h, line)
    }
}

/** Eyebrow: 12sp Exo 2 700 uppercase, tracking 0.16em, in [accent] (`NeonEyebrow`) */
internal fun Context.neonEyebrow(
    text: String,
    accent: Int,
): TextView =
    setupText(text.uppercase(), accent, Gravity.CENTER, 1) {
        if (AppTheme.isNeon) {
            typeface = AppFonts.body(context, WEIGHT_BOLD)
            sizeSp(12f)
            letterSpacing = .16f
            lineHeightSp(12f * 1.2f)
        } else {
            applyTextRole(TextRole.LABEL_SMALL)
        }
    }

/**
 * The outline `Button` / `TextButton` (`ui/components/Button.kt`) with a text label and an optional Font Awesome
 * [glyph]; [showProgress] swaps the label for a 32dp spinner, as the old submit buttons did while loading.
 */
internal class SetupButton(
    context: Context,
    text: String,
    glyph: String? = null,
    textButton: Boolean = true,
    private val accent: Int = NeonColors.VOLT,
) : SetupSurface(context, NeonSurfaces.button(accent)) {
    private val label = context.setupText(text, maxLines = 1) { applyTextRole(TextRole.LABEL_LARGE) }
    private val glyphView: TextView? =
        glyph?.let {
            TextView(context).apply {
                faGlyph(16f)
                this.text = it
            }
        }
    private val spinner = RingSpinner(context, AppTheme.palette.border, 0, 4f)
    private val row: LinearLayout

    init {
        contentDescription = text
        row =
            horizontal(context).apply {
                gravity = Gravity.CENTER
                if (textButton) {
                    setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
                } else {
                    setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                }
                minimumHeight = context.dp(if (textButton) 32 else 40) + context.dp(8)
                glyphView?.let {
                    addView(it)
                    addView(context.hGap(8f))
                }
                addView(label)
                addView(spinner, LinearLayout.LayoutParams(context.dp(32), context.dp(32)))
            }
        spinner.visibility = View.GONE
        addView(row, frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        onFocusVisual = { recolor() }
        recolor()
    }

    var showProgress: Boolean = false
        set(value) {
            field = value
            label.visibility = if (value) View.GONE else View.VISIBLE
            glyphView?.visibility = if (value) View.GONE else View.VISIBLE
            spinner.visibility = if (value) View.VISIBLE else View.GONE
        }

    fun setText(text: String) {
        label.text = text
        contentDescription = text
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        recolor()
    }

    private fun recolor() {
        val c = NeonSurfaces.buttonContent(isFocused, isEnabled, accent)
        label.setTextColor(c)
        glyphView?.setTextColor(c)
    }
}

internal fun Context.setupButton(
    @StringRes text: Int,
    glyph: String? = null,
    onClick: () -> Unit,
): SetupButton =
    SetupButton(this, getString(text), glyph).apply {
        layoutParams = wrap(Gravity.CENTER_HORIZONTAL)
        setOnClickListener { onClick() }
    }

/**
 * An input field for text customised for TV entry (`EditTextBox`, the value form): 40dp tall and at least 280dp wide,
 * `surfaceVariant` fills (an error fill while [isInputValid] says no), square with a 1dp edge on the WeaselTV theme
 * (the accent when focused) and a pill elsewhere.
 */
internal class SetupEditText(
    context: Context,
    private val accent: Int = NeonColors.VOLT,
) : EditText(context) {
    var isInputValid: (String) -> Boolean = { true }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()

    init {
        background = null
        applyTextRole(TextRole.BODY_LARGE)
        setTextColor(AppTheme.palette.onSurface)
        setHintTextColor(withAlpha(AppTheme.palette.onSurfaceVariant, .6f))
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        minHeight = context.dp(40)
        minWidth = context.dp(280)
        minimumWidth = context.dp(280)
    }

    /** Re-check [isInputValid] (eg after the screen's error state changed) */
    fun refreshValidity() = invalidate()

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int,
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val p = AppTheme.palette
        val neon = AppTheme.isNeon
        val valid = isInputValid(text?.toString().orEmpty())
        fill.color =
            when {
                !valid -> withAlpha(p.errorContainer, .75f)
                !isEnabled -> withAlpha(p.surfaceVariant, .25f)
                isFocused -> withAlpha(p.surfaceVariant, .8f)
                else -> withAlpha(p.surfaceVariant, .6f)
            }
        val l = scrollX.toFloat()
        r.set(l, 0f, l + width, height.toFloat())
        if (neon) canvas.drawRect(r, fill) else canvas.drawRoundRect(r, height / 2f, height / 2f, fill)
        super.draw(canvas)
        if (neon) {
            val w = context.dpF(1f)
            border.strokeWidth = w
            border.color =
                when {
                    !valid -> p.error
                    isFocused -> accent
                    else -> NeonColors.LINE2
                }
            r.inset(w / 2, w / 2)
            canvas.drawRect(r, border)
        }
    }

    companion object {
        const val PLAIN = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        const val URI = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        const val PASSWORD = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
}

/** `CircularProgress`: the theme's `border` colour, no track */
internal fun Context.circularProgress(sizeDp: Float): View =
    RingSpinner(this, AppTheme.palette.border, 0, 4f).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

/**
 * Fills the space with a loading indicator and takes focus (`LoadingPage`): the rotating loading artwork on the WeaselTV
 * theme (the mascot when the flavor has none), a 48dp spinner elsewhere.
 */
internal class SetupLoadingView(
    context: Context,
) : FrameLayout(context) {
    private val trip = NavTiming.current()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (AppTheme.isNeon) {
            val kids = LoadingArtworkRotation.lastProfileWasKids(context)
            val artworks =
                LoadingArtworkRotation.resourceNames(kids).mapNotNull { name ->
                    context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
                }
            val artwork = LoadingArtworkRotation.next(context, artworks, kids)
            LoadingArtworkRotation.peek(context, artworks, kids)?.let { LoadingArtworkPreload.request(context.resources, it) }
            if (artwork != null) {
                setBackgroundColor(0xFF000000.toInt())
                addView(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageResource(artwork)
                    },
                    frame(),
                )
            } else {
                val mascot = context.resources.getIdentifier("weaselplex_mascot", "drawable", context.packageName)
                if (mascot != 0) {
                    addView(
                        ImageView(context).apply { setImageResource(mascot) },
                        frame(context.dp(96), context.dp(96), Gravity.CENTER),
                    )
                }
            }
        } else {
            addView(RingSpinner(context, AppTheme.palette.border, 0, 4f), frame(context.dp(48), context.dp(48), Gravity.CENTER))
        }
    }

    override fun onDetachedFromWindow() {
        NavTiming.mark("content after loading page", of = trip)
        super.onDetachedFromWindow()
    }
}

/** Sends the app logs from the error page (the old `ErrorViewModel`) */
@HiltViewModel
class SetupErrorViewModel
    @Inject
    constructor(
        private val serverReportService: ServerReportService,
    ) : ViewModel() {
        fun sendLogs() {
            viewModelScope.launchIO { serverReportService.sendAppLogs() }
        }
    }

/**
 * Displays an error message and its exception chain (`ErrorMessageContent`): on the WeaselTV theme an 80dp red outline
 * icon box and the Condensed 30 title; Try again when [onRetry] is set, Send app logs, then the details. Long chains
 * scroll with the D-pad.
 */
internal fun Context.errorMessageView(
    message: String?,
    exception: Throwable?,
    onSendLogs: () -> Unit,
    onRetry: (() -> Unit)? = null,
): View {
    val neon = AppTheme.isNeon
    val p = AppTheme.palette
    val column =
        vertical(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

    fun add(
        view: View,
        params: LinearLayout.LayoutParams = wrap(),
    ) {
        if (column.childCount > 0) params.topMargin = dp(16)
        column.addView(view, params)
    }
    if (neon) {
        val box =
            object : FrameLayout(this) {
                private val paint =
                    Paint().apply {
                        style = Paint.Style.STROKE
                        strokeWidth = context.dpF(1f)
                        color = NeonColors.RED
                    }

                init {
                    setWillNotDraw(false)
                }

                override fun onDraw(canvas: Canvas) {
                    val h = paint.strokeWidth / 2
                    canvas.drawRect(h, h, width - h, height - h, paint)
                }
            }
        box.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.lb_setup_ic_warning)
                imageTintList = ColorStateList.valueOf(NeonColors.RED)
            },
            frame(dp(40), dp(40), Gravity.CENTER),
        )
        add(box, LinearLayout.LayoutParams(dp(80), dp(80)))
    }
    val title = getString(R.string.compose_error_message_title).let { if (neon) it.uppercase() else it }
    add(
        setupText(title, if (neon) NeonColors.TEXT else p.error) {
            if (neon) neonHeader(30f, TextRole.TITLE_MEDIUM) else applyTextRole(TextRole.TITLE_MEDIUM)
        },
    )
    onRetry?.let { add(setupButton(R.string.try_again) { it() }, wrap()) }
    add(setupButton(R.string.send_app_logs) { onSendLogs() }, wrap())
    message?.let {
        add(
            setupText(it, if (neon) NeonColors.MID else p.error) {
                applyTextRole(if (neon) TextRole.BODY_MEDIUM else TextRole.TITLE_LARGE)
            },
        )
    }
    exception?.localizedMessage?.let { add(setupText(it, p.error) { applyTextRole(TextRole.TITLE_MEDIUM) }) }
    var cause = exception?.cause
    while (cause != null) {
        cause.localizedMessage?.let { add(setupText("Caused by: $it", p.error) { applyTextRole(TextRole.TITLE_SMALL) }) }
        cause = cause.cause
    }
    return object : ScrollView(this) {
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val max = context.dp(480)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val capped = if (mode == MeasureSpec.UNSPECIFIED) max else minOf(max, size)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        addView(column)
    }
}

/**
 * A dialog list row (the tv-material3 `ListItem` with the Neon list-item recipe): an optional leading Font Awesome glyph
 * or icon, the headline and an optional supporting line.
 */
internal class SetupListItem(
    context: Context,
    headline: String,
    supporting: String? = null,
    leadingGlyph: String? = null,
    @DrawableRes leadingIcon: Int = 0,
    private val leadingTint: Int? = null,
    accent: Int = NeonColors.VOLT,
) : SetupSurface(context, NeonSurfaces.listItem(accent)) {
    private val headlineView = context.setupText(headline) { applyTextRole(TextRole.TITLE_MEDIUM) }
    private val supportingView = supporting?.let { context.setupText(it) { applyTextRole(TextRole.BODY_SMALL) } }
    private val glyphView =
        leadingGlyph?.let {
            TextView(context).apply {
                faGlyph(18f)
                text = it
                gravity = Gravity.CENTER
            }
        }
    private val iconView =
        if (leadingIcon != 0) {
            ImageView(context).apply { setImageResource(leadingIcon) }
        } else {
            null
        }

    init {
        contentDescription = headline
        minimumHeight = context.dp(if (supporting != null) 64 else 48)
        val row =
            horizontal(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                glyphView?.let {
                    addView(it, LinearLayout.LayoutParams(context.dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(context.hGap(8f))
                }
                iconView?.let {
                    addView(it, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
                    addView(context.hGap(8f))
                }
                addView(
                    vertical(context).apply {
                        addView(headlineView)
                        supportingView?.let { addView(it) }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        addView(row, frame(height = ViewGroup.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_VERTICAL))
        onFocusVisual = { recolor() }
        recolor()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // A row disabled for the long-press guard stays focusable, as the Compose list kept focus on it
        isFocusable = true
        recolor()
    }

    private fun recolor() {
        val base = NeonSurfaces.listItemContent(isFocused)
        val c = if (isEnabled) base else withAlpha(base, NeonColors.DISABLED_ALPHA)
        headlineView.setTextColor(c)
        supportingView?.setTextColor(c)
        glyphView?.setTextColor(leadingTint ?: c)
        iconView?.imageTintList = ColorStateList.valueOf(leadingTint ?: c)
    }
}

/** tv-material3 `surfaceColorAtElevation`: the primary colour over `surface`, more of it the higher it sits */
internal fun surfaceAtElevation(elevationDp: Float): Int {
    val p = AppTheme.palette
    if (elevationDp <= 0f) return p.surface
    val alpha = ((4.5f * ln(elevationDp + 1)) + 2f) / 100f
    return compositeOver(withAlpha(p.primary, alpha), p.surface)
}

/** A title that shrinks to fit one line (`FittedPanelTitle`) */
internal fun TextView.fitOneLine(
    minSp: Int,
    maxSp: Int,
) {
    maxLines = 1
    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, minSp, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
}
