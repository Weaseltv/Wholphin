package com.github.damontecres.wholphin.leanback.settings

import android.annotation.SuppressLint
import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.leanback.settings.SettingsLook.setLineHeightPx
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import java.util.Locale

/**
 * The heading of every settings panel (the old `PreferencePanelHeader`). On the WeaselTV theme: the title in
 * upper case, Audiowide 40sp shrinking to fit up to three lines without breaking a word (down to 18sp), 32dp from
 * the panel edges, with the Neon section rule 6dp below. Other themes: `headlineSmall`, centred.
 */
@SuppressLint("ViewConstructor")
internal class SettingsPanelHeader(
    context: Context,
    accent: Int,
    /** Home settings' plain `titleLarge` heading (the old `TitleText`) instead of the panel heading */
    private val plain: Boolean = false,
) : LinearLayout(context) {
    private val titleView: TextView
    private val neon = SettingsLook.neon && !plain
    private var rule: SettingsRuleView? = null

    /** The page accent (the rule) */
    var accent: Int = accent
        set(value) {
            field = value
            rule?.accent = value
        }

    init {
        orientation = VERTICAL
        if (plain) setPadding(0, context.dp(8), 0, context.dp(4)) else setPadding(0, context.dp(8), 0, context.dp(8))
        clipChildren = false
        clipToPadding = false
        titleView =
            if (plain) {
                AppCompatTextView(context).apply {
                    applyTextRole(TextRole.TITLE_LARGE)
                    setTextColor(AppTheme.palette.onSurface)
                    setPadding(context.dp(8), 0, context.dp(8), 0)
                }
            } else if (neon) {
                FittedTitleView(context).apply {
                    setPadding(context.dp(32), 0, context.dp(32), 0)
                }
            } else {
                AppCompatTextView(context).apply {
                    applyTextRole(TextRole.HEADLINE_SMALL)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(AppTheme.palette.onSurface)
                }
            }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (neon) {
            val rule = SettingsRuleView(context, accent).also { this.rule = it }
            addView(
                rule,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    // The rule view is the 10dp glow with the 3dp line at its bottom: the line sits 6dp under the title
                    topMargin = context.dp(6) - (context.dp(10) - context.dp(3))
                },
            )
        }
    }

    var title: CharSequence
        get() = titleView.text
        set(value) {
            titleView.text = if (neon) value.toString().uppercase(Locale.getDefault()) else value
        }
}

/**
 * A title that picks the largest size from 40sp down to 18sp at which every word fits the width and the text takes
 * at most three lines (the old `FittedPanelTitle`), with a 1.25 line height and the display font.
 */
internal class FittedTitleView(
    context: Context,
) : AppCompatTextView(context) {
    private var fittedFor: Pair<Int, CharSequence>? = null

    init {
        typeface = AppFonts.display(context)
        letterSpacing = .06f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(AppTheme.palette.onSurface)
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        applySize(MAX_SP)
    }

    private fun applySize(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setLineHeightPx(context, sp * 1.25f)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (width > 0 && fittedFor != (width to text)) {
            fittedFor = width to text
            applySize(fit(width))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fit(width: Int): Float {
        val content = text?.toString().orEmpty()
        val words = content.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val paint = TextPaint(paint)
        val metrics = resources.displayMetrics
        var size = MAX_SP
        while (size > MIN_SP) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
            val longest = words.maxOfOrNull { paint.measureText(it) } ?: 0f
            if (longest <= width) {
                val layout =
                    StaticLayout.Builder
                        .obtain(content, 0, content.length, paint, width)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                        .build()
                if (layout.lineCount <= 3) return size
            }
            size -= 1f
        }
        return MIN_SP
    }

    private companion object {
        const val MAX_SP = 40f
        const val MIN_SP = 18f
    }
}
