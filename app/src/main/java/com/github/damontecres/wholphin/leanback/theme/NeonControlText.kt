package com.github.damontecres.wholphin.leanback.theme

import android.util.TypedValue
import android.widget.TextView
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout

/**
 * A text style for Views: either explicit Neon values (Exo 2 / Audiowide with size, weight, line height and
 * tracking) or a stock [TextRole] (the old `MaterialTheme.typography` slot), optionally with a size or weight change.
 * Apply it with [applyTextStyle]. Built by [NeonType].
 */
data class ControlTextStyle(
    val sizeSp: Float,
    val weight: Int,
    /** Line height as a multiple of the size */
    val lineHeight: Float,
    val trackingEm: Float,
    /** Audiowide (display) instead of Exo 2 */
    val display: Boolean,
    /** When set, the stock slot is used instead of the explicit values */
    val fallback: TextRole? = null,
    val fallbackSizeSp: Float? = null,
    val fallbackWeight: Int? = null,
) {
    /** This style at another size (Compose `style.copy(fontSize = …)`, keeping the line-height ratio) */
    fun withSize(sp: Float): ControlTextStyle = if (fallback != null) copy(fallbackSizeSp = sp) else copy(sizeSp = sp)

    companion object {
        fun of(
            role: TextRole,
            sizeSp: Float? = null,
            weight: Int? = null,
        ) = ControlTextStyle(0f, 400, 1f, 0f, false, role, sizeSp, weight)
    }
}

/** Apply a [ControlTextStyle] (font, size, line height, tracking) */
fun TextView.applyTextStyle(style: ControlTextStyle) {
    val fallback = style.fallback
    if (fallback != null) {
        applyTextRole(fallback, style.fallbackSizeSp, style.fallbackWeight)
        return
    }
    typeface = if (style.display) AppFonts.display(context) else AppFonts.body(context, style.weight)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp)
    letterSpacing = style.trackingEm
    val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.sizeSp * style.lineHeight, resources.displayMetrics)
    val fontHeight = paint.getFontMetricsInt(null)
    setLineSpacing(px - fontHeight, 1f)
}

/**
 * The Neon roles of `ui/theme/NeonTypography.kt` that do not map onto a stock slot. Each returns the stock slot when
 * the WeaselTV theme is not active, so call sites can use them unconditionally:
 * `title.applyTextStyle(NeonType.dialogTitle())`.
 */
object NeonType {
    private val neon: Boolean get() = AppTheme.isNeon

    private fun header(
        sizeSp: Float,
        fallback: ControlTextStyle,
    ): ControlTextStyle = if (neon) ControlTextStyle(sizeSp, 400, 1.2f, .06f, true) else fallback

    private fun exo(
        sizeSp: Float,
        weight: Int,
        lineHeight: Float,
        trackingEm: Float,
        fallback: ControlTextStyle,
    ): ControlTextStyle = if (neon) ControlTextStyle(sizeSp, weight, lineHeight, trackingEm, false) else fallback

    /** Home and details header title, 48–52. Uppercase in code. */
    fun hero(sizeSp: Float = 48f) = header(sizeSp, ControlTextStyle.of(TextRole.HEADLINE_MEDIUM))

    /** Library, search, settings, requests: 40 */
    fun pageTitle() = header(40f, ControlTextStyle.of(TextRole.HEADLINE_SMALL))

    /** Dialog and panel titles: 26 */
    fun dialogTitle() = header(26f, ControlTextStyle.of(TextRole.HEADLINE_SMALL))

    /** Player title: 28 */
    fun playerTitle() = header(28f, ControlTextStyle.of(TextRole.HEADLINE_SMALL))

    /** Any other Audiowide 400 uppercase title (eg the 30sp state title) */
    fun condensedTitle(sizeSp: Float) = header(sizeSp, ControlTextStyle.of(TextRole.HEADLINE_SMALL))

    /** Row titles and section rules (the approved Home title size and tracking) */
    fun sectionTitle(): ControlTextStyle =
        if (neon) {
            val size = ApprovedHomeLayout.TITLE_SIZE_SP.toFloat()
            ControlTextStyle(size, 400, 1.2f, (ApprovedHomeLayout.TITLE_TRACKING_TENTHS_SP / 10f) / size, true)
        } else {
            ControlTextStyle.of(TextRole.TITLE_LARGE)
        }

    /** Clocks, timecodes and count labels: Exo 2 SemiBold */
    fun numeral(sizeSp: Float) = exo(sizeSp, 600, 1.2f, 0f, ControlTextStyle.of(TextRole.TITLE_MEDIUM, sizeSp = sizeSp))

    /** The wordmark: rail 20, sign-in 34, screensaver 36 */
    fun wordmark(sizeSp: Float) = header(sizeSp, ControlTextStyle.of(TextRole.TITLE_LARGE, sizeSp = sizeSp, weight = 800))

    /** 12 / 700 / upper / tracking 0.16em */
    fun eyebrow() = exo(12f, 700, 1.2f, .16f, ControlTextStyle.of(TextRole.LABEL_SMALL))

    /** Home kicker: Exo 2 SemiBold, half the main heading size, 0.18 em tracking */
    fun homeKicker(titleSizeSp: Float) = exo(titleSizeSp * .5f, 600, 1.2f, .18f, ControlTextStyle.of(TextRole.LABEL_SMALL))

    /** Count / right label: 12 / 700 / upper / tracking 0.12em */
    fun count() = exo(12f, 700, 1.2f, .12f, ControlTextStyle.of(TextRole.LABEL_SMALL))

    /** Badge: 11 / 700 / upper / tracking 0.10em */
    fun badge() = exo(11f, 700, 1.2f, .1f, ControlTextStyle.of(TextRole.LABEL_SMALL))

    /** Button: 16 / 700 / upper / tracking 0.06em */
    fun button() = exo(16f, 700, 1f, .06f, ControlTextStyle.of(TextRole.LABEL_LARGE))

    /** Tab: 15 / 700 / upper / tracking 0.06em */
    fun tab() = exo(15f, 700, 1f, .06f, ControlTextStyle.of(TextRole.LABEL_LARGE))

    /** Chip: 14 / 600 */
    fun chip() = exo(14f, 600, 1f, 0f, ControlTextStyle.of(TextRole.LABEL_MEDIUM))

    /** Sidebar labels: Exo 2 Medium 15, including the active page */
    @Suppress("UNUSED_PARAMETER")
    fun railLabel(selected: Boolean = false) = exo(15f, 500, 1.2f, 0f, ControlTextStyle.of(TextRole.LABEL_MEDIUM))

    /** Row title / episode title: 17 / 600 */
    fun rowTitle() = exo(17f, 600, 1.25f, 0f, ControlTextStyle.of(TextRole.TITLE_MEDIUM))

    /** Row subtitle: 14 / 400 */
    fun rowSubtitle() = exo(14f, 400, 1.3f, 0f, ControlTextStyle.of(TextRole.BODY_MEDIUM))

    /** Card title under the poster: 14 / 600 */
    fun cardTitle() = exo(14f, 600, 1.3f, 0f, ControlTextStyle.of(TextRole.TITLE_SMALL))

    /** Card subtitle: 13 / 400 */
    fun cardSubtitle() = exo(13f, 400, 1.3f, 0f, ControlTextStyle.of(TextRole.BODY_SMALL))

    /** Body / overview: 15 / 400 / 1.5 */
    fun body(sizeSp: Float = 15f) = exo(sizeSp, 400, 1.5f, 0f, ControlTextStyle.of(TextRole.BODY_MEDIUM))

    /** Settings label 17 / 500 */
    fun settingsLabel() = exo(17f, 500, 1.3f, 0f, ControlTextStyle.of(TextRole.TITLE_MEDIUM))

    /** Settings value 15 / 600 */
    fun settingsValue() = exo(15f, 600, 1.3f, 0f, ControlTextStyle.of(TextRole.BODY_MEDIUM))

    /** Small: 13 / 400 */
    fun small() = exo(13f, 400, 1.4f, 0f, ControlTextStyle.of(TextRole.BODY_SMALL))
}

/** Display text is uppercased in code, never in strings; other themes keep the case (Compose `neonUpper()`) */
fun String.neonUpper(): String = if (AppTheme.isNeon) uppercase() else this

/** The eyebrow (`NeonEyebrow`): 12sp Exo 2 700 uppercase, tracking 0.16em, in the accent, one line */
fun TextView.applyNeonEyebrow(
    text: String,
    accent: Int,
) {
    this.text = text.uppercase()
    setTextColor(accent)
    applyTextStyle(NeonType.eyebrow())
    maxLines = 1
}
