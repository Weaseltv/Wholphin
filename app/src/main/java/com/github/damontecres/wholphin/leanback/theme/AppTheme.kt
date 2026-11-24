package com.github.damontecres.wholphin.leanback.theme

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppThemeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The View UI's theme (no-Compose UI, 2026-11-22). Replaces `WholphinTheme`, `LocalTheme`, `isWeaselTv()` and
 * `MaterialTheme.colorScheme` / `MaterialTheme.typography`.
 *
 * The shell calls [update] whenever `interfacePreferences.appThemeColors` changes; Views read [palette] when they
 * bind and may collect [themeFlow] to repaint.
 */
object AppTheme {
    private val _theme =
        MutableStateFlow(
            runCatching { AppThemeColors.valueOf(BuildConfig.DEFAULT_THEME) }.getOrDefault(AppThemeColors.PURPLE),
        )
    val themeFlow: StateFlow<AppThemeColors> = _theme.asStateFlow()

    val theme: AppThemeColors get() = _theme.value

    val palette: AppPalette get() = AppPalettes.of(_theme.value)

    /** Replaces Compose `isWeaselTv()`: the Neon Board recipe (accent borders, glows, badges) is active */
    val isNeon: Boolean get() = _theme.value == AppThemeColors.WEASELTV

    /** The packaged Exo 2 / Audiowide fonts are used (always in the weaselfin flavor, or with the WeaselTV theme) */
    val useWeaselFonts: Boolean get() = BuildConfig.FLAVOR == "weaselfin" || isNeon

    fun update(theme: AppThemeColors) {
        _theme.value = if (theme == AppThemeColors.UNRECOGNIZED) AppThemeColors.PURPLE else theme
    }
}

/** Neon Board tokens (`ui/theme/Neon.kt` `NeonBoard`) as ARGB ints */
object NeonColors {
    const val STAGE = 0xFF050608.toInt()
    const val VIDEO = 0xFF000000.toInt()
    const val CARD = 0xFF0B0D12.toInt()
    const val CARD2 = 0xFF11141B.toInt()
    const val WELL = 0xFF08090D.toInt()
    const val LINE = 0xFF1C2029.toInt()
    const val LINE2 = 0xFF2A303B.toInt()
    const val TEXT = 0xFFF2F5F9.toInt()
    const val MID = 0xFF8B95A5.toInt()
    const val LOW = 0xFF6B7686.toInt()
    const val ON_ACCENT = 0xFF050608.toInt()
    const val VOLT = 0xFFC6FF00.toInt()
    const val GREEN = 0xFF3DFF6E.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val ORANGE = 0xFFFF5200.toInt()
    const val ORANGE_GLOW = 0xFFFF7900.toInt()
    const val YELLOW = 0xFFFFE600.toInt()
    const val RED = 0xFFFF2A3D.toInt()
    const val ELECTRIC_BLUE = 0xFF0088FF.toInt()
    const val TEAL = 0xFF00C2A0.toInt()
    const val FUCHSIA = 0xFFFF1A8C.toInt()
    const val VIOLET = 0xFF7B4DFF.toInt()
    const val ICE_WHITE = 0xFFE8F4FF.toInt()
    const val WARN = 0xFFF5B93D.toInt()

    /** Player buttons over video */
    const val GLASS = 0xB8050608.toInt()

    /** Behind dialogs and the options menu */
    const val SCRIM = 0xAD050608.toInt()

    /** Focus transition length in ms; the only motion that focus produces */
    const val FOCUS_MS = 120L
    const val DISABLED_ALPHA = .45f

    /** Cards scale to this on focus (Neon recipe) */
    const val CARD_FOCUS_SCALE = 1.06f

    /** Selected chips and the focused row fill: accent 14 % over [CARD] */
    fun chipOn(accent: Int): Int = compositeOver(withAlpha(accent, .14f), CARD)
}

/** Owner-approved section border/glow pairs (`ui/theme/NeonSectionPalette.kt`) */
object NeonSections {
    data class Colors(
        val border: Int,
        val glow: Int,
    )

    val Search = Colors(0xFF0A2CFF.toInt(), 0xFF00A3FF.toInt())
    val Watchlist = Colors(NeonColors.ORANGE, NeonColors.ORANGE_GLOW)
    val Requests = Colors(0xFF009A93.toInt(), 0xFF00F2D6.toInt())
    val Home = Colors(0xFF0B8A42.toInt(), 0xFF00E04B.toInt())
    val Collections = Colors(0xFFFFD300.toInt(), 0xFFFFFF33.toInt())
    val Movies = Colors(0xFF00B8D9.toInt(), 0xFF00E1FF.toInt())
    val TvShows = Colors(0xFFE8112D.toInt(), 0xFFFF1A1A.toInt())
    val StandUpComedy = Colors(0xFF5A00FF.toInt(), 0xFF9B30FF.toInt())
    val Sports = Colors(0xFFFF0099.toInt(), 0xFFFF85D0.toInt())

    private val all = listOf(Search, Watchlist, Requests, Home, Collections, Movies, TvShows, StandUpComedy, Sports)

    /** The glow colour paired with a section border colour, or the colour itself */
    fun glowFor(accent: Int): Int {
        val opaque = accent or 0xFF000000.toInt()
        val glow = all.firstOrNull { it.border == opaque }?.glow ?: return accent
        return (glow and 0x00FFFFFF) or (accent and 0xFF000000.toInt())
    }
}

fun withAlpha(
    color: Int,
    alpha: Float,
): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

fun compositeOver(
    top: Int,
    bottom: Int,
): Int {
    val a = (top ushr 24) / 255f

    fun ch(shift: Int): Int = (((top shr shift) and 0xFF) * a + ((bottom shr shift) and 0xFF) * (1 - a)).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Packaged fonts: Exo 2 (body) and Audiowide (display) ship only in the weaselfin flavor, resolved by name */
object AppFonts {
    private var body: Map<Int, Typeface>? = null
    private var display: Typeface? = null
    private var icons: Typeface? = null

    private fun font(
        context: Context,
        name: String,
    ): Typeface? {
        val id = context.resources.getIdentifier(name, "font", context.packageName)
        return if (id != 0) runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() else null
    }

    /** @param weight 400 regular, 500 medium, 600 semibold, 700+ bold */
    fun body(
        context: Context,
        weight: Int = 400,
    ): Typeface {
        if (!AppTheme.useWeaselFonts) return Typeface.create(Typeface.DEFAULT, weight, false)
        val map =
            body ?: mapOf(
                400 to font(context, "exo2_regular"),
                500 to font(context, "exo2_medium"),
                600 to font(context, "exo2_semibold"),
                700 to font(context, "exo2_bold"),
            ).filterValues { it != null }.mapValues { it.value!! }.also { body = it }
        val key =
            when {
                weight >= 700 -> 700
                weight >= 600 -> 600
                weight >= 500 -> 500
                else -> 400
            }
        return map[key] ?: map[400] ?: Typeface.create(Typeface.DEFAULT, weight, false)
    }

    fun display(context: Context): Typeface {
        if (!AppTheme.useWeaselFonts) return Typeface.DEFAULT
        return display ?: (font(context, "audiowide_regular") ?: Typeface.DEFAULT).also { display = it }
    }

    /** Font Awesome solid (`res/font/fa_solid_900.ttf`), used for glyph icons in the old UI */
    fun icons(context: Context): Typeface =
        icons ?: (ResourcesCompat.getFont(context, R.font.fa_solid_900) ?: Typeface.DEFAULT).also { icons = it }
}

/**
 * Text roles matching the old tv-material3 typography slots. With the packaged WeaselPlex fonts the sizes are the
 * Neon ones (`rememberNeonTypography`), otherwise the stock Material 3 ones.
 */
enum class TextRole(
    val neonSp: Float,
    val neonWeight: Int,
    val neonLineHeight: Float,
    val neonTrackingEm: Float,
    val neonDisplay: Boolean,
    val stockSp: Float,
    val stockWeight: Int,
    val stockLineHeightSp: Float,
    val stockTrackingSp: Float,
) {
    DISPLAY_LARGE(52f, 400, 1.2f, .06f, true, 57f, 400, 64f, -.25f),
    DISPLAY_MEDIUM(48f, 400, 1.2f, .06f, true, 45f, 400, 52f, 0f),
    DISPLAY_SMALL(40f, 400, 1.2f, .06f, true, 36f, 400, 44f, 0f),
    HEADLINE_LARGE(32f, 400, 1.2f, .06f, true, 32f, 400, 40f, 0f),
    HEADLINE_MEDIUM(28f, 400, 1.2f, .06f, true, 28f, 400, 36f, 0f),
    HEADLINE_SMALL(24f, 400, 1.2f, .06f, true, 24f, 400, 32f, 0f),
    TITLE_LARGE(22f, 400, 1.2f, .06f, true, 22f, 400, 28f, 0f),
    TITLE_MEDIUM(17f, 600, 1.25f, 0f, false, 16f, 500, 24f, .15f),
    TITLE_SMALL(15f, 600, 1.3f, 0f, false, 14f, 500, 20f, .1f),
    BODY_LARGE(16f, 400, 1.5f, 0f, false, 16f, 400, 24f, .5f),
    BODY_MEDIUM(15f, 400, 1.4f, 0f, false, 14f, 400, 20f, .25f),
    BODY_SMALL(13f, 400, 1.4f, 0f, false, 12f, 400, 16f, .4f),
    LABEL_LARGE(16f, 700, 1f, .06f, false, 14f, 500, 20f, .1f),
    LABEL_MEDIUM(15f, 500, 1f, 0f, false, 12f, 500, 16f, .5f),
    LABEL_SMALL(12f, 700, 1.2f, .1f, false, 11f, 500, 16f, .5f),
}

/**
 * Apply a [TextRole] (font, size, line height, tracking). [sizeSp] overrides the size the way the old code did
 * `style.copy(fontSize = …)`; [weight] overrides the weight.
 */
fun TextView.applyTextRole(
    role: TextRole,
    sizeSp: Float? = null,
    weight: Int? = null,
) {
    val weasel = AppTheme.useWeaselFonts
    val size = sizeSp ?: if (weasel) role.neonSp else role.stockSp
    val w = weight ?: if (weasel) role.neonWeight else role.stockWeight
    typeface =
        if (weasel && role.neonDisplay && weight == null) AppFonts.display(context) else AppFonts.body(context, w)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    if (weasel) {
        letterSpacing = role.neonTrackingEm
        setLineHeightSp(size * role.neonLineHeight)
    } else {
        letterSpacing = if (size > 0) role.stockTrackingSp / size else 0f
        setLineHeightSp(role.stockLineHeightSp * (size / role.stockSp))
    }
}

private fun TextView.setLineHeightSp(sp: Float) {
    val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    val fontHeight = paint.getFontMetricsInt(null)
    if (px.toInt() != fontHeight) setLineSpacing(px - fontHeight, 1f)
}

/** dp to px */
fun Context.dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

fun Context.dp(value: Int): Int = dp(value.toFloat())

fun Context.dpF(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
