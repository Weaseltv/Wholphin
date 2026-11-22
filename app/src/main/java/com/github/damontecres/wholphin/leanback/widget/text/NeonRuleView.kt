package com.github.damontecres.wholphin.leanback.widget.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonGlowSpec
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.NeonSizes
import com.github.damontecres.wholphin.leanback.theme.allowDrawingOutsideParent
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.withAlpha

/**
 * The Neon section rule (`NeonRule` / `NeonSectionRule`): a [thicknessDp] bar in the accent (3dp, the approved Home
 * divider) with a [glowHeightDp] glow fading up from its bottom edge. The view is [thicknessDp] tall and the glow
 * reaches above it, outside its bounds, as before (its parent must not clip children; it switches that off on its
 * direct parent). It is `GONE` off the WeaselTV theme, where the old rule drew nothing and took no space.
 *
 * For a section rule that reaches the page edges while its label keeps a gutter (`InsetNeonSectionRules`), give it
 * negative start/end margins of the gutter. [section] uses the brighter Home glow colour on Home, as
 * `NeonSectionRule` did. The accent is [ruleAccent], else the view tree's.
 */
class NeonRuleView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val paint = Paint()
        private val glowPaint = Paint()

        var thicknessDp: Float = NeonSizes.RULE
            set(value) {
                field = value
                requestLayout()
            }
        var glowHeightDp: Float = NeonGlowSpec.RULE
        var glowStrength: Float = ApprovedHomeLayout.DIVIDER_GLOW_STRENGTH / 100f

        /** `NeonSectionRule`: Home's rule wears its glow colour */
        var section: Boolean = false

        /** The accent; null takes the view tree's on attach */
        var ruleAccent: Int? = null
            set(value) {
                field = value
                invalidate()
            }

        private var resolvedAccent: Int = 0

        init {
            visibility = if (AppTheme.isNeon) VISIBLE else GONE
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            allowDrawingOutsideParent()
            resolvedAccent = resolveNeonScope().accentOrDefault
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(
                getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                context.dp(thicknessDp),
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (!AppTheme.isNeon) return
            var accent = ruleAccent ?: resolvedAccent
            if (section && (accent or 0xFF000000.toInt()) == NeonSections.Home.border) accent = neonGlowAccent(accent)
            val density = resources.displayMetrics.density
            val h = height.toFloat()
            val glowH = glowHeightDp * density
            if (glowH > 0f && glowStrength > 0f) {
                glowPaint.shader =
                    LinearGradient(
                        0f,
                        h - glowH,
                        0f,
                        h,
                        0,
                        withAlpha(neonGlowAccent(accent), glowStrength.coerceIn(0f, 1f)),
                        Shader.TileMode.CLAMP,
                    )
                canvas.drawRect(0f, h - glowH, width.toFloat(), h, glowPaint)
            }
            paint.color = accent
            canvas.drawRect(0f, h - thicknessDp * density, width.toFloat(), h, paint)
        }
    }
