package com.github.damontecres.wholphin.leanback.widget.text

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.QuickDetailsText
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.ui.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.EnumSet
import java.util.Locale
import kotlin.time.Duration

/** The " • " separator of the quick details line (`AnnotatedString.Builder.dot()`) */
const val QUICK_DETAILS_DOT = "  •  "

/** The yellow of a filled star (`FilledStarColor`) */
const val FILLED_STAR_COLOR = 0xFFFFC700.toInt()

/** The dark yellow of an empty star (`EmptyStarColor`) */
const val EMPTY_STAR_COLOR = 0xFF5D4A0A.toInt()

/** The display toggles the user enabled (Settings > Interface; the old `LocalInterfaceCustomization`) */
fun enabledDisplayToggles(prefs: AppPreferences): Set<DisplayToggle> =
    prefs.interfacePreferences.displayTogglesList.let {
        if (it.isEmpty()) EnumSet.noneOf(DisplayToggle::class.java) else EnumSet.copyOf(it)
    }

/** An inline icon the size of the text, centred on it (Compose `InlineTextContent` with `PlaceholderVerticalAlign.TextCenter`) */
internal class TextCenterImageSpan(
    drawable: Drawable,
) : ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM) {
    override fun getSize(
        paint: android.graphics.Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: android.graphics.Paint.FontMetricsInt?,
    ): Int {
        val size = paint.textSize.toInt()
        drawable.setBounds(0, 0, size, size)
        return size
    }

    override fun draw(
        canvas: android.graphics.Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: android.graphics.Paint,
    ) {
        val fm = paint.fontMetrics
        val size = paint.textSize
        // Centre the icon on the text (between ascent and descent around the baseline)
        val centerY = y + (fm.ascent + fm.descent) / 2f
        canvas.save()
        canvas.translate(x, centerY - size / 2f)
        drawable.draw(canvas)
        canvas.restore()
    }
}

/** Append an inline icon that is as tall as the text */
internal fun SpannableStringBuilder.appendIcon(
    context: Context,
    res: Int,
    tint: Int? = null,
) {
    val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return
    tint?.let { drawable.setTint(it) }
    val start = length
    append("￼")
    setSpan(TextCenterImageSpan(drawable), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/**
 * The quick details line (`QuickDetails`, rendering `BaseItem.ui.quickDetailsText`): the parts joined with " • ",
 * then " • official rating", " • 7.5★" (yellow star the text's size) and " • 85%🍅" (fresh at 60 % and above,
 * rotten below) as enabled in the display toggles, then " • Ends at 9:41 PM" for [timeRemaining] (kept current with
 * the clock) or [endsAt]. Each piece wraps as a unit (a FlowRow of texts, two lines at most each), titleSmall,
 * onSurface by default.
 */
class QuickDetailsView(
    context: Context,
) : FlowRowLayout(context) {
    private var textStyle: ControlTextStyle = ControlTextStyle.of(TextRole.TITLE_SMALL)
    private var textColor: Int = AppTheme.palette.onSurface
    private var timeRemaining: Duration? = null
    private var endsAt: LocalDateTime? = null
    private var endsAtView: TextView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var clockJob: Job? = null

    /** The text style (the old `textStyle`, titleSmall by default) */
    fun setTextStyle(style: ControlTextStyle) {
        textStyle = style
        (0 until childCount).forEach { (getChildAt(it) as? TextView)?.applyTextStyle(style) }
    }

    fun setTextColor(color: Int) {
        textColor = color
        (0 until childCount).forEach { (getChildAt(it) as? TextView)?.setTextColor(color) }
    }

    /**
     * Show [details] (null shows only the end time), with [enabledToggles] from [enabledDisplayToggles] (all by
     * default, as the old composition-local default).
     */
    fun bind(
        details: QuickDetailsText?,
        timeRemaining: Duration?,
        endsAt: LocalDateTime? = null,
        enabledToggles: Set<DisplayToggle> = EnumSet.allOf(DisplayToggle::class.java),
    ) {
        removeAllViews()
        endsAtView = null
        this.timeRemaining = timeRemaining
        this.endsAt = endsAt
        if (details != null) {
            addPiece(SpannableStringBuilder(details.parts.joinToString(QUICK_DETAILS_DOT)))
            if (DisplayToggle.OFFICIAL_RATING in enabledToggles) {
                details.officialRating?.let { addPiece(SpannableStringBuilder(QUICK_DETAILS_DOT).append(it)) }
            }
            if (DisplayToggle.COMMUNITY_RATING in enabledToggles) {
                details.communityRating?.let {
                    addPiece(
                        SpannableStringBuilder(QUICK_DETAILS_DOT)
                            .append(String.format(Locale.getDefault(), "%.1f", it))
                            .apply { appendIcon(context, R.drawable.lb_widget_ctl_ic_star, FILLED_STAR_COLOR) },
                    )
                }
            }
            if (DisplayToggle.CRITIC_RATING in enabledToggles) {
                details.criticRating?.let {
                    addPiece(
                        SpannableStringBuilder(QUICK_DETAILS_DOT)
                            .append("${it.toInt()}%")
                            .apply {
                                appendIcon(
                                    context,
                                    if (it >= 60f) R.drawable.ic_rotten_tomatoes_fresh else R.drawable.ic_rotten_tomatoes_rotten,
                                )
                            },
                    )
                }
            }
        }
        if (timeRemaining != null || endsAt != null) {
            endsAtView = addPiece(SpannableStringBuilder(endsAtText(clockNow())))
        }
        updateClockSubscription()
    }

    private fun clockNow(): LocalDateTime = AppClock.now.value

    private fun endsAtText(now: LocalDateTime): String {
        val end = timeRemaining?.let { now.plusSeconds(it.inWholeSeconds) } ?: endsAt ?: return ""
        return QUICK_DETAILS_DOT + context.getString(R.string.ends_at, formatTime(context, end))
    }

    private fun addPiece(text: CharSequence): TextView {
        val view =
            TextView(context).apply {
                this.text = text
                applyTextStyle(textStyle)
                setTextColor(textColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return view
    }

    private fun updateClockSubscription() {
        clockJob?.cancel()
        clockJob = null
        if (timeRemaining != null && isAttachedToWindow) {
            clockJob = scope.launch { AppClock.now.collect { endsAtView?.text = endsAtText(it) } }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateClockSubscription()
    }

    override fun onDetachedFromWindow() {
        clockJob?.cancel()
        clockJob = null
        super.onDetachedFromWindow()
    }
}
