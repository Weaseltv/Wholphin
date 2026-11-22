package com.github.damontecres.wholphin.leanback.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.animation.PathInterpolator
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.AppThemeColors
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Card recipes for the View UI (no-Compose UI, 2026-11-22): what `ui/theme/Neon.kt` (neonCardBorder / neonCardGlow /
 * neonCardScale / neonCardShape, NeonBadge, neonProgress, NeonRule), `ui/theme/CollectionCardStyle.kt`,
 * `ui/cards/WatchedIcon.kt` and `ui/UiConstants.kt` `Cards` gave the Compose cards, as plain values and painters.
 *
 * Every value follows the old code: on the WeaselTV (Neon Board) theme the card appearance in the page's [PageStyle]
 * (corner radius, border width/opacity, glow spread/opacity, focus scale, accent override); on every other theme the
 * tv-material3 `Card` defaults (8dp corners, 3dp `border` colour focus border, 1.1 focus scale, no glow).
 * The widgets in `leanback.widget.card` use these; see `runbooks/no-compose/WIDGETS-CARDS.md`.
 */

/** Card sizes in dp, from `ui/UiConstants.kt` `Cards` and `ui/cards/PersonRow.kt` */
object CardDimens {
    private const val WEASELFIN = BuildConfig.FLAVOR == "weaselfin"

    /** `Cards.height2x3`: poster height of the standard 2:3 card (owner-approved 170dp in the weaselfin flavor) */
    val HEIGHT_2X3_DP: Int = if (WEASELFIN) ApprovedHomeLayout.CARD_HEIGHT_DP else 172

    /** `Cards.heightEpisode` */
    const val HEIGHT_EPISODE_DP = 128

    /** `Cards.playedPercentHeight`: progress bar height on every theme but WeaselTV */
    const val PLAYED_PERCENT_HEIGHT_DP = 6

    /** `NeonBoard.Size.ProgressCard`: progress bar height on the WeaselTV theme */
    const val NEON_PROGRESS_HEIGHT_DP = 3

    /** `personRowCardWidth` */
    const val PERSON_CARD_WIDTH_DP = 108

    /** Space between cards in a row: `ApprovedHomeLayout.CARD_SPACING_DP` in the weaselfin flavor, else 16dp */
    val ROW_SPACING_DP: Int = if (WEASELFIN) ApprovedHomeLayout.CARD_SPACING_DP else 16

    /** Column gap of a card and its caption (`Arrangement.spacedBy(4.dp)` in every card column) */
    const val CAPTION_GAP_DP = 4

    /** `SlidingCardText`: total caption padding; 4dp of it is below the text when focused, 12dp when not */
    const val SLIDE_TOTAL_DP = 12
    const val SLIDE_FOCUSED_BOTTOM_DP = 4

    /** `NeonBoard.Size.OverscanX / 2`: the page gutter section rules bleed into on the WeaselTV theme */
    const val NEON_RULE_INSET_START_DP = 24

    /** Section rules reach 8dp further at the end of every page but Home (`NavDrawer`) */
    const val NEON_RULE_INSET_END_DP = 8
}

/** Card outline: see [cardFocusLook] */
enum class CardShape {
    /** `neonCardShape()`: the appearance's corner radius on WeaselTV (square by default), 8dp elsewhere */
    DEFAULT,

    /** Genre cards: 8dp corners on every theme */
    ROUNDED_8,

    /** People: a circle, focus ring follows it */
    CIRCLE,
}

/** What fills the card surface behind its image (tv-material3 `CardDefaults.colors(containerColor = …)`) */
enum class CardContainer {
    /** `containerColor = Color.Transparent` (grid, season, person, discover, genre, studio cards) */
    TRANSPARENT,

    /** tv-material3 default: `surfaceVariant` (banner and chapter cards) */
    SURFACE_VARIANT,

    /** `surfaceColorAtElevation(1.dp)` (the View more card) */
    ELEVATED,
}

/**
 * Resolved focus treatment of one card. Sizes in px. The card draws [glowColor] behind its shape with a blur of
 * [glowRadiusPx] (API 28+, as tv-material3 did) and strokes [borderWidthPx] centred on [borderRadiusPx]'s outline over
 * its content while focused or pressed; it scales to [focusedScale] / [pressedScale].
 */
data class CardFocusLook(
    val cornerRadiusPx: Float,
    val circle: Boolean,
    val borderRadiusPx: Float,
    val borderCircle: Boolean,
    val focusedScale: Float,
    val pressedScale: Float,
    val borderWidthPx: Float,
    val borderColor: Int,
    val glowRadiusPx: Float,
    val glowColor: Int,
    val containerColor: Int,
)

/**
 * The focus look of a card with accent [accent] on a page with [style]: port of `neonCardShape`, `neonCardBorder`,
 * `neonCardGlow`, `neonCardScale` and the tv-material3 `CardDefaults` fallbacks.
 *
 * [style] supplies `LocalHomeCardAppearance` ([PageStyle.homeCardAppearance]), `LocalHomeRowAccent`
 * ([PageStyle.rowAccent]) and `LocalHomeCardBorderAccent` ([PageStyle.cardBorderAccent]).
 */
fun cardFocusLook(
    context: Context,
    style: PageStyle,
    accent: Int,
    shape: CardShape = CardShape.DEFAULT,
    container: CardContainer = CardContainer.TRANSPARENT,
): CardFocusLook {
    val palette = AppTheme.palette
    val containerColor =
        when (container) {
            CardContainer.TRANSPARENT -> 0
            CardContainer.SURFACE_VARIANT -> palette.surfaceVariant
            CardContainer.ELEVATED -> surfaceColorAtElevation1dp()
        }
    val eight = context.dpF(8f)
    if (!AppTheme.isNeon) {
        // tv-material3: CardDefaults.shape() = 8dp, border() = 3dp colorScheme.border on the 8dp shape (people: on the
        // circle), scale() = 1.1 focused and pressed, glow() = none
        return CardFocusLook(
            cornerRadiusPx = eight,
            circle = shape == CardShape.CIRCLE,
            borderRadiusPx = eight,
            borderCircle = shape == CardShape.CIRCLE,
            focusedScale = 1.1f,
            pressedScale = 1.1f,
            borderWidthPx = context.dpF(3f),
            borderColor = palette.border,
            glowRadiusPx = 0f,
            glowColor = 0,
            containerColor = containerColor,
        )
    }
    val appearance = style.homeCardAppearance
    val radius =
        when (shape) {
            CardShape.DEFAULT -> context.dpF(appearance.cornerRadiusDp.toFloat())
            CardShape.ROUNDED_8 -> eight
            CardShape.CIRCLE -> 0f
        }
    val homeAccent = style.homeCardAccent(accent)
    val hasBorder = appearance.borderWidthDp > 0 && appearance.borderOpacityPercent > 0
    return CardFocusLook(
        cornerRadiusPx = radius,
        circle = shape == CardShape.CIRCLE,
        borderRadiusPx = radius,
        borderCircle = shape == CardShape.CIRCLE,
        focusedScale = appearance.focusScalePercent / 100f,
        pressedScale = .97f,
        borderWidthPx = if (hasBorder) context.dpF(appearance.borderWidthDp.toFloat()) else 0f,
        borderColor = withAlpha(style.cardBorderAccent ?: homeAccent, appearance.borderOpacityPercent / 100f),
        glowRadiusPx = context.dpF(appearance.glowSpreadDp.toFloat()),
        glowColor = withAlpha(neonGlowAccent(homeAccent), appearance.glowOpacityPercent / 100f),
        containerColor = containerColor,
    )
}

/** tv-material3 `surfaceColorAtElevation(1.dp)`: the surface tint at 5.1 % over `surface` (Neon tint is `card`) */
fun surfaceColorAtElevation1dp(): Int {
    val palette = AppTheme.palette
    val tint = if (AppTheme.isNeon) NeonColors.CARD else palette.primary
    val alpha = ((4.5f * ln(1f + 1f)) + 2f) / 100f
    return compositeOver(withAlpha(tint, alpha), palette.surface)
}

/**
 * Focus motion of tv-material3 surfaces (`SurfaceScale.kt`), which every Compose card used: scale up in 300 ms on
 * focus, back in 500 ms on unfocus, 120 ms to the pressed scale, 300 ms on release, all with `CubicBezier(0, 0, .2, 1)`.
 * Border and glow switch with the focus state, without animation.
 */
object CardFocusMotion {
    const val FOCUS_MS = 300L
    const val UNFOCUS_MS = 500L
    const val PRESS_MS = 120L
    const val RELEASE_MS = 300L

    fun interpolator() = PathInterpolator(0f, 0f, .2f, 1f)
}

/** Draws a card's focus glow (behind) and border (over the content), as tv-material3 `tvSurfaceGlow` / `tvSurfaceBorder` */
class CardFocusPainter {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    /** Glow: a transparent shape with a coloured shadow layer of the glow radius. tv-material3 drew it on API 28+ only. */
    fun drawGlow(
        canvas: Canvas,
        width: Float,
        height: Float,
        look: CardFocusLook,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || look.glowRadiusPx <= 0f) return
        glowPaint.color = 0
        glowPaint.setShadowLayer(look.glowRadiusPx, 0f, 0f, look.glowColor)
        rect.set(0f, 0f, width, height)
        if (look.circle) {
            canvas.drawOval(rect, glowPaint)
        } else {
            canvas.drawRoundRect(rect, look.cornerRadiusPx, look.cornerRadiusPx, glowPaint)
        }
    }

    /** Border: a stroke centred on the outline (half of it outside the card), as tv-material3 drew it */
    fun drawBorder(
        canvas: Canvas,
        width: Float,
        height: Float,
        look: CardFocusLook,
    ) {
        if (look.borderWidthPx <= 0f || (look.borderColor ushr 24) == 0) return
        borderPaint.strokeWidth = look.borderWidthPx
        borderPaint.color = look.borderColor
        rect.set(0f, 0f, width, height)
        if (look.borderCircle) {
            canvas.drawOval(rect, borderPaint)
        } else {
            canvas.drawRoundRect(rect, look.borderRadiusPx, look.borderRadiusPx, borderPaint)
        }
    }
}

/**
 * `neonRuleBelow`: a [glowHeightPx] gradient (transparent to the glow accent at [glowStrength]) ending at the bottom
 * edge, and a [thicknessPx] line in [accent] along the bottom. Draw into a box at least max(thickness, glow) tall.
 */
class NeonRulePainter {
    private val glowPaint = Paint()
    private val linePaint = Paint()
    private var shaderKey: Long = 0

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        accent: Int,
        thicknessPx: Float,
        glowHeightPx: Float,
        glowStrength: Float,
    ) {
        if (width <= 0f) return
        if (glowHeightPx > 0f && glowStrength > 0f) {
            val glow = withAlpha(neonGlowAccent(accent), glowStrength.coerceIn(0f, 1f))
            val key = (glow.toLong() shl 32) xor (height.toBits().toLong() * 31 + glowHeightPx.toBits())
            if (key != shaderKey || glowPaint.shader == null) {
                glowPaint.shader =
                    LinearGradient(0f, height - glowHeightPx, 0f, height, 0, glow, Shader.TileMode.CLAMP)
                shaderKey = key
            }
            canvas.drawRect(0f, height - glowHeightPx, width, height, glowPaint)
        }
        linePaint.color = accent
        canvas.drawRect(0f, height - thicknessPx, width, height, linePaint)
    }
}

/**
 * `NeonBadge` background: a radial glow 12dp past the badge, a fill (card at 72 %, or the accent when [filled]) and a
 * 1dp accent outline centred on the edge. Draws outside its bounds: the badge's parent must not clip children.
 */
class NeonBadgePainter(
    context: Context,
) {
    private val glowPx = context.dpF(12f)
    private val strokePx = context.dpF(1f)
    private val glowPaint = Paint()
    private val fillPaint = Paint()
    private val strokePaint = Paint().apply { style = Paint.Style.STROKE }
    private var shaderKey = ""

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        accent: Int,
        filled: Boolean,
    ) {
        val alpha = if (filled) .5f else .3f
        val glow = withAlpha(neonGlowAccent(accent), alpha * .5f)
        val radius = max(width, height) / 2 + glowPx
        val key = "$glow/$width/$height"
        if (key != shaderKey) {
            glowPaint.shader = RadialGradient(width / 2, height / 2, radius, glow, 0, Shader.TileMode.CLAMP)
            shaderKey = key
        }
        canvas.drawRect(-glowPx, -glowPx, width + glowPx, height + glowPx, glowPaint)
        fillPaint.color = if (filled) accent else withAlpha(NeonColors.CARD, .72f)
        canvas.drawRect(0f, 0f, width, height, fillPaint)
        strokePaint.strokeWidth = strokePx
        strokePaint.color = accent
        canvas.drawRect(0f, 0f, width, height, strokePaint)
    }
}

/** `WatchedIconBackground()` */
fun watchedIconBackground(): Int =
    when (AppTheme.theme) {
        AppThemeColors.OLED_BLACK -> AppTheme.palette.secondaryContainer
        AppThemeColors.WEASELTV -> NeonColors.GREEN
        else -> AppTheme.palette.border or 0xFF000000.toInt()
    }

/** `WatchedIconColor()`: dark ink on the Neon square, white elsewhere */
fun watchedIconColor(): Int =
    when (AppTheme.theme) {
        AppThemeColors.WEASELTV -> NeonColors.ON_ACCENT
        else -> 0xFFFFFFFF.toInt()
    }

/**
 * `CollectionCardStyle`: the page style a grid card of a collection (box set) [item] uses on the WeaselTV theme.
 * Streaming-service collections take [streamingAppearance] and their provider's accent and border override; other
 * collections take [appearance] and the owner's poster palette. The type badge is hidden on these cards
 * (`LocalCollectionCardAppearance`), expressed here as [PageStyle.hidePosterTypeBadge]. Any other item: unchanged.
 */
fun PageStyle.forCollectionCard(
    item: BaseItem?,
    appearance: HomeCardAppearance = ApprovedCollectionCardAppearanceView,
    streamingAppearance: HomeCardAppearance = appearance,
): PageStyle {
    if (!AppTheme.isNeon || item?.type != BaseItemKind.BOX_SET) return this
    val providerAccent = streamingProviderAccent(item.name)
    val focusAppearance = if (providerAccent != null) streamingAppearance else appearance
    return copy(
        homeCardAppearance = focusAppearance.copy(accentIndex = 0, borderOpacityPercent = 100),
        rowAccent = providerAccent ?: collectionPosterAccent(item.name),
        cardBorderAccent = streamingProviderBorderAccent(item.name),
        hidePosterTypeBadge = true,
    )
}

/**
 * The style of a Home row's cards (`HomePageCardContent` / `HomePageViewMoreCard`): the row's saved card appearance,
 * the card's row accent (`LocalHomeRowAccent`) and border override (`LocalHomeCardBorderAccent`).
 */
fun PageStyle.forHomeRowCard(
    viewOptions: HomeRowViewOptions,
    rowAccent: Int?,
    borderAccent: Int? = null,
): PageStyle = copy(homeCardAppearance = viewOptions.cardAppearance, rowAccent = rowAccent, cardBorderAccent = borderAccent)

/** Additional collections and sports use the owner's supplied poster palettes (`collectionPosterAccent`) */
fun collectionPosterAccent(name: String?): Int? =
    when (
        name
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace("’", "")
            ?.replace("'", "")
    ) {
        "a24 mood" -> 0xFF6440FF.toInt()
        "afis 100 greatest", "afi 100 greatest" -> 0xFFFFCC40.toInt()
        "amblin magic" -> 0xFF4094FF.toInt()
        "anime feature night", "anime feature nights" -> 0xFFFF40A7.toInt()
        "box office champs" -> 0xFF55FF40.toInt()
        "capes and cowls", "capes & cowls" -> 0xFFFF2649.toInt()
        "edge of your seat" -> 0xFFFF3930.toInt()
        "family movie night" -> 0xFFFFD640.toInt()
        "front lines" -> 0xFF8DFF40.toInt()
        "game day" -> 0xFF2EFF8B.toInt()
        "gold statue" -> 0xFFFFD140.toInt()
        "groovy 70s" -> 0xFFFF8B2A.toInt()
        "grown up toons", "grown-up toons" -> 0xFFFF40DF.toInt()
        "imdb most popular" -> 0xFFFFCE2C.toInt()
        "imdb top 250" -> 0xFFFFCF31.toInt()
        "ink and pixels", "ink & pixels" -> 0xFF40FFD9.toInt()
        "mic drop" -> 0xFFFF4918.toInt()
        "one and done", "one & done" -> 0xFF31D2FF.toInt()
        "out of this world" -> 0xFF39D5FF.toInt()
        "saddle up" -> 0xFFFF9134.toInt()
        "silver screen" -> 0xFF407EFF.toInt()
        "sword and sorcery", "sword & sorcery" -> 0xFF9840FF.toInt()
        "the auteur shelf" -> 0xFFFFC940.toInt()
        "the complete set" -> 0xFF4067FF.toInt()
        "the sandlerverse" -> 0xFFFF8B2E.toInt()
        "the usual suspects" -> 0xFFFF5A39.toInt()
        "tmdb top rated" -> 0xFF39FFE2.toInt()
        "turn it up" -> 0xFFFF33D4.toInt()
        "whodunit", "whodunnit" -> 0xFFFFC932.toInt()
        "boxing" -> 0xFFF29E19.toInt()
        "ufc fight night" -> 0xFF3B8BF2.toInt()
        "ufc on abc" -> 0xFF1FF2D4.toInt()
        "ufc on espn" -> 0xFFF22519.toInt()
        "ufc ppv" -> 0xFFFECD30.toInt()
        else -> null
    }

/**
 * `rememberIdColor`: a consistent colour for a UUID (genre and studio tiles), or [nullColor] (surfaceVariant) for none.
 * Same arithmetic as the old code, including its handling of negative hashes.
 */
fun idColor(
    id: UUID?,
    alpha: Float = 1f,
    nullColor: Int = AppTheme.palette.surfaceVariant,
): Int {
    if (id == null) return nullColor
    val hash = id.hashCode()
    val hue = (hash % 360).toFloat()
    val saturation = 0.6f + ((hash / 360) % 40).toFloat() / 100f
    val brightness = 0.4f + ((hash / 14400) % 30).toFloat() / 100f
    val c = brightness * saturation
    val x = c * (1 - abs((hue / 60f) % 2f - 1))
    val m = brightness - c
    val (r, g, b) =
        when {
            hue < 60 -> Triple(c, x, 0f)
            hue < 120 -> Triple(x, c, 0f)
            hue < 180 -> Triple(0f, c, x)
            hue < 240 -> Triple(0f, x, c)
            hue < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

    fun ch(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f).roundToInt()
    return ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}
