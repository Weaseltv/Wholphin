package com.github.damontecres.wholphin.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography

/**
 * Exo 2 / Audiowide for the WeaselTV theme (`01-tokens-tv.md` § Type).
 *
 * The font files live ONLY in the `weaselfin` flavor's `res/font`, so main code cannot
 * name `R.font.exo2_regular` without breaking every upstream flavor's build. They are
 * resolved by name at runtime, the same way [com.github.damontecres.wholphin.ui.nav.WeaselNavIcons]
 * resolves its drawables: on a flavor without them the lookup returns 0 and the platform
 * default family is used, so nothing changes for upstream.
 */
object NeonFonts {
    @Volatile
    private var cache: Pair<FontFamily, FontFamily>? = null

    /** Exo 2 (copy) and Audiowide (display), or the defaults when not shipped. */
    fun families(context: Context): Pair<FontFamily, FontFamily> {
        cache?.let { return it }
        val res = context.resources
        val pkg = context.packageName

        fun id(name: String): Int = res.getIdentifier(name, "font", pkg)

        fun family(vararg fonts: Pair<String, FontWeight>): FontFamily {
            val loaded = fonts.mapNotNull { (name, weight) -> id(name).takeIf { it != 0 }?.let { Font(it, weight) } }
            return if (loaded.isEmpty()) FontFamily.Default else FontFamily(loaded)
        }

        val body =
            family(
                "exo2_regular" to FontWeight.Normal,
                "exo2_medium" to FontWeight.Medium,
                "exo2_semibold" to FontWeight.SemiBold,
                "exo2_bold" to FontWeight.Bold,
            )
        val display =
            family(
                "audiowide_regular" to FontWeight.Normal,
            )
        return (body to display).also { cache = it }
    }
}

/**
 * The tv-material3 [Typography] slots on Exo 2 / Audiowide. Sizes stay close to the M3 defaults so
 * upstream layouts that read a slot keep their proportions; the Neon-specific roles
 * (hero, page title, eyebrow, badge, timecode…) are explicit styles in [NeonType].
 */
@Composable
fun rememberNeonTypography(): Typography {
    val context = LocalContext.current
    return remember(context) {
        val (body, display) = NeonFonts.families(context)
        val stock = Typography()

        fun d(
            base: TextStyle,
            size: TextUnit,
            weight: FontWeight = FontWeight.Normal,
        ) = base.copy(fontFamily = display, fontWeight = weight, fontSize = size, lineHeight = size * 1.2f, letterSpacing = .06.em)

        fun b(
            base: TextStyle,
            size: TextUnit,
            weight: FontWeight,
            lineHeight: Float = 1.3f,
            tracking: TextUnit = 0.sp,
        ) = base.copy(fontFamily = body, fontWeight = weight, fontSize = size, lineHeight = size * lineHeight, letterSpacing = tracking)

        Typography(
            displayLarge = d(stock.displayLarge, 52.sp),
            displayMedium = d(stock.displayMedium, 48.sp),
            displaySmall = d(stock.displaySmall, 40.sp),
            headlineLarge = d(stock.headlineLarge, 32.sp),
            headlineMedium = d(stock.headlineMedium, 28.sp),
            headlineSmall = d(stock.headlineSmall, 24.sp),
            titleLarge = d(stock.titleLarge, 22.sp),
            titleMedium = b(stock.titleMedium, 17.sp, FontWeight.SemiBold, 1.25f),
            titleSmall = b(stock.titleSmall, 15.sp, FontWeight.SemiBold),
            bodyLarge = b(stock.bodyLarge, 16.sp, FontWeight.Normal, 1.5f),
            bodyMedium = b(stock.bodyMedium, 15.sp, FontWeight.Normal, 1.4f),
            bodySmall = b(stock.bodySmall, 13.sp, FontWeight.Normal, 1.4f),
            labelLarge = b(stock.labelLarge, 16.sp, FontWeight.Bold, 1f, .06.em),
            labelMedium = b(stock.labelMedium, 15.sp, FontWeight.Medium, 1f),
            labelSmall = b(stock.labelSmall, 12.sp, FontWeight.Bold, 1.2f, .1.em),
        )
    }
}

/**
 * The Neon roles that do not map onto an M3 slot. Each returns the theme's current
 * style unchanged when the WeaselTV theme is not active, so call sites can use them
 * unconditionally.
 */
object NeonType {
    @Composable
    @ReadOnlyComposable
    private fun display(): TextStyle = MaterialTheme.typography.titleLarge

    @Composable
    @ReadOnlyComposable
    private fun body(): TextStyle = MaterialTheme.typography.bodyMedium

    @Composable
    @ReadOnlyComposable
    private fun header(
        size: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        fallback: TextStyle,
    ): TextStyle =
        if (isWeaselTv()) {
            display().copy(fontSize = size, fontWeight = weight, lineHeight = size * 1.2f, letterSpacing = .06.em)
        } else {
            fallback
        }

    @Composable
    @ReadOnlyComposable
    private fun exo(
        size: TextUnit,
        weight: FontWeight,
        lineHeight: Float,
        tracking: TextUnit,
        fallback: TextStyle,
    ): TextStyle =
        if (isWeaselTv()) {
            body().copy(fontSize = size, fontWeight = weight, lineHeight = size * lineHeight, letterSpacing = tracking)
        } else {
            fallback
        }

    /** Home and details header title, 48–52. Uppercase in code. */
    @Composable
    @ReadOnlyComposable
    fun hero(size: TextUnit = 48.sp): TextStyle = header(size, fallback = MaterialTheme.typography.headlineMedium)

    /** Library, guide, search, settings, requests: 40. */
    @Composable
    @ReadOnlyComposable
    fun pageTitle(): TextStyle = header(40.sp, fallback = MaterialTheme.typography.headlineSmall)

    /** Dialog and panel titles: 26–28. */
    @Composable
    @ReadOnlyComposable
    fun dialogTitle(): TextStyle = header(26.sp, fallback = MaterialTheme.typography.headlineSmall)

    /** Player title: 28. */
    @Composable
    @ReadOnlyComposable
    fun playerTitle(): TextStyle = header(28.sp, fallback = MaterialTheme.typography.headlineSmall)

    /** Any other Audiowide 400 uppercase title (e.g. the 30sp state title). */
    @Composable
    @ReadOnlyComposable
    fun condensedTitle(size: TextUnit): TextStyle = header(size, fallback = MaterialTheme.typography.headlineSmall)

    /** Row titles and section rules: 22. */
    @Composable
    @ReadOnlyComposable
    fun sectionTitle(): TextStyle = header(22.sp, fallback = MaterialTheme.typography.titleLarge)

    /** Clocks, timecodes and count labels use Exo 2 for compact, readable numerals. */
    @Composable
    @ReadOnlyComposable
    fun numeral(size: TextUnit): TextStyle =
        exo(size, FontWeight.SemiBold, 1.2f, 0.sp, MaterialTheme.typography.titleMedium.copy(fontSize = size))

    /** The wordmark: rail 20, sign-in 34, screensaver 36. */
    @Composable
    @ReadOnlyComposable
    fun wordmark(size: TextUnit): TextStyle =
        header(size, fallback = MaterialTheme.typography.titleLarge.copy(fontSize = size, fontWeight = FontWeight.ExtraBold))

    /** 12 / 700 / upper / tracking 0.16em. */
    @Composable
    @ReadOnlyComposable
    fun eyebrow(): TextStyle = exo(12.sp, FontWeight.Bold, 1.2f, .16.em, MaterialTheme.typography.labelSmall)

    /** Home kicker: Exo 2 SemiBold, half the main heading size, 0.18 em tracking. */
    @Composable
    @ReadOnlyComposable
    fun homeKicker(titleSize: TextUnit): TextStyle =
        exo(titleSize * .5f, FontWeight.SemiBold, 1.2f, .18.em, MaterialTheme.typography.labelSmall)

    /** Count / right label: 12 / 700 / upper / tracking 0.12em. */
    @Composable
    @ReadOnlyComposable
    fun count(): TextStyle = exo(12.sp, FontWeight.Bold, 1.2f, .12.em, MaterialTheme.typography.labelSmall)

    /** Badge: 11 / 700 / upper / tracking 0.10em. */
    @Composable
    @ReadOnlyComposable
    fun badge(): TextStyle = exo(11.sp, FontWeight.Bold, 1.2f, .1.em, MaterialTheme.typography.labelSmall)

    /** Button: 16 / 700 / upper / tracking 0.06em. */
    @Composable
    @ReadOnlyComposable
    fun button(): TextStyle = exo(16.sp, FontWeight.Bold, 1f, .06.em, MaterialTheme.typography.labelLarge)

    /** Tab: 15 / 700 / upper / tracking 0.06em. */
    @Composable
    @ReadOnlyComposable
    fun tab(): TextStyle = exo(15.sp, FontWeight.Bold, 1f, .06.em, MaterialTheme.typography.labelLarge)

    /** Chip: 14 / 600. */
    @Composable
    @ReadOnlyComposable
    fun chip(): TextStyle = exo(14.sp, FontWeight.SemiBold, 1f, 0.sp, MaterialTheme.typography.labelMedium)

    /** Sidebar labels use Exo 2 Medium at 15 sp, including the active page. */
    @Composable
    @ReadOnlyComposable
    fun railLabel(selected: Boolean): TextStyle =
        exo(15.sp, FontWeight.Medium, 1.2f, 0.sp, MaterialTheme.typography.labelMedium)

    /** Row title / episode title: 17 / 600. */
    @Composable
    @ReadOnlyComposable
    fun rowTitle(): TextStyle = exo(17.sp, FontWeight.SemiBold, 1.25f, 0.sp, MaterialTheme.typography.titleMedium)

    /** Row subtitle: 14 / 400. */
    @Composable
    @ReadOnlyComposable
    fun rowSubtitle(): TextStyle = exo(14.sp, FontWeight.Normal, 1.3f, 0.sp, MaterialTheme.typography.bodyMedium)

    /** Card title under the poster: 14 / 600. */
    @Composable
    @ReadOnlyComposable
    fun cardTitle(): TextStyle = exo(14.sp, FontWeight.SemiBold, 1.3f, 0.sp, MaterialTheme.typography.titleSmall)

    /** Card subtitle: 13 / 400. */
    @Composable
    @ReadOnlyComposable
    fun cardSubtitle(): TextStyle = exo(13.sp, FontWeight.Normal, 1.3f, 0.sp, MaterialTheme.typography.bodySmall)

    /** Body / overview: 15 / 400 / 1.5. */
    @Composable
    @ReadOnlyComposable
    fun body(size: TextUnit = 15.sp): TextStyle = exo(size, FontWeight.Normal, 1.5f, 0.sp, MaterialTheme.typography.bodyMedium)

    /** Settings label 17 / 500 and value 15 / 600. */
    @Composable
    @ReadOnlyComposable
    fun settingsLabel(): TextStyle = exo(17.sp, FontWeight.Medium, 1.3f, 0.sp, MaterialTheme.typography.titleMedium)

    @Composable
    @ReadOnlyComposable
    fun settingsValue(): TextStyle = exo(15.sp, FontWeight.SemiBold, 1.3f, 0.sp, MaterialTheme.typography.bodyMedium)

    /** Small: 13 / 400. */
    @Composable
    @ReadOnlyComposable
    fun small(): TextStyle = exo(13.sp, FontWeight.Normal, 1.4f, 0.sp, MaterialTheme.typography.bodySmall)
}

/** Display text is uppercased in code, never in strings; other themes keep the case. */
@Composable
@ReadOnlyComposable
fun String.neonUpper(): String = if (isWeaselTv()) uppercase() else this
