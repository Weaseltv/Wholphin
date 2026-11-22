package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.util.TypedValue
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Text styles of the old card composables (no-Compose UI). Private to the cards package; the CONTROLS area owns the
 * general text widgets.
 *
 * Line heights follow Material 3 text in Compose (`LineHeightStyle(Center, Trim.None)`, no font padding): every line,
 * the first and last included, is exactly the line height tall, so captions measure as they did.
 */

/** A resolved text style: size and line height in sp, letter spacing in em */
internal data class TextSpec(
    val sizeSp: Float,
    val weight: Int,
    val lineHeightSp: Float,
    val trackingEm: Float,
    val display: Boolean = false,
) {
    fun withSize(size: Float): TextSpec = copy(sizeSp = size)
}

/**
 * A tv-material3 typography slot ([role]) as `MaterialTheme.typography.x` gave it: Neon sizes with the packaged fonts,
 * stock Material 3 sizes otherwise. [sizeSp] behaves like `style.copy(fontSize = …)` with the line height scaled in
 * proportion (the old code always copied both); [weight] like `fontWeight = …`.
 */
internal fun roleSpec(
    role: TextRole,
    sizeSp: Float? = null,
    weight: Int? = null,
): TextSpec {
    val weasel = AppTheme.useWeaselFonts
    val baseSize = if (weasel) role.neonSp else role.stockSp
    val size = sizeSp ?: baseSize
    val baseLineHeight = if (weasel) role.neonSp * role.neonLineHeight else role.stockLineHeightSp
    val tracking = if (weasel) role.neonTrackingEm else if (size > 0) role.stockTrackingSp / size else 0f
    return TextSpec(
        sizeSp = size,
        weight = weight ?: if (weasel) role.neonWeight else role.stockWeight,
        lineHeightSp = baseLineHeight * (size / baseSize),
        trackingEm = tracking,
        display = weasel && role.neonDisplay,
    )
}

/** The slot's line height in sp, unchanged by a size override (`Text(fontSize = …)` without a new line height) */
internal fun roleLineHeightSp(role: TextRole): Float =
    if (AppTheme.useWeaselFonts) role.neonSp * role.neonLineHeight else role.stockLineHeightSp

/** `NeonType` Exo 2 role (WeaselTV theme): size, weight, line height multiple, tracking in em */
internal fun neonSpec(
    sizeSp: Float,
    weight: Int,
    lineHeight: Float,
    trackingEm: Float = 0f,
): TextSpec = TextSpec(sizeSp, weight, sizeSp * lineHeight, trackingEm)

internal object CardTextStyles {
    /** `PosterCardTitle`: Neon 14/600/1.3, otherwise bodyMedium SemiBold */
    fun posterTitle(): TextSpec = if (AppTheme.isNeon) neonSpec(14f, 600, 1.3f) else roleSpec(TextRole.BODY_MEDIUM, weight = 600)

    /** `PosterCardSubtitle`: Neon 13/400/1.3, otherwise bodySmall Normal */
    fun posterSubtitle(): TextSpec = if (AppTheme.isNeon) neonSpec(13f, 400, 1.3f) else roleSpec(TextRole.BODY_SMALL, weight = 400)

    /** `NeonType.rowSubtitle()` / titleMedium: the loading and error rows' message */
    fun rowSubtitle(): TextSpec = if (AppTheme.isNeon) neonSpec(14f, 400, 1.3f) else roleSpec(TextRole.TITLE_MEDIUM)

    /** Default `Text` style (bodyLarge) at [sizeSp], keeping bodyLarge's line height as `Text(fontSize = …)` did */
    fun bodyLargeAt(sizeSp: Float): TextSpec {
        val base = roleSpec(TextRole.BODY_LARGE)
        val trackingSp = base.trackingEm * base.sizeSp
        return base.copy(sizeSp = sizeSp, trackingEm = if (sizeSp > 0) trackingSp / sizeSp else 0f)
    }

    /** bodySmall with an explicit size and line height (`PersonCard`) */
    fun bodySmall(
        sizeSp: Float,
        lineHeightSp: Float,
        weight: Int,
    ): TextSpec {
        val base = roleSpec(TextRole.BODY_SMALL)
        val trackingSp = base.trackingEm * base.sizeSp
        return TextSpec(sizeSp, weight, lineHeightSp, if (sizeSp > 0) trackingSp / sizeSp else 0f)
    }
}

internal fun Context.spPx(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

/** dp to px rounded as Compose `Dp.roundToPx()` did (image fill sizes) */
internal fun Context.dpRound(dp: Float): Int = (dp * resources.displayMetrics.density).roundToInt()

/** Apply [spec]: font, size, tracking and exact line height. Call after setting horizontal padding. */
internal fun TextView.applySpec(spec: TextSpec) {
    val ctx = context
    typeface = if (spec.display) AppFonts.display(ctx) else AppFonts.body(ctx, spec.weight)
    // A display family has one weight; Compose synthesised bold for heavier requests
    paint.isFakeBoldText = spec.display && spec.weight >= 600
    setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.sizeSp)
    letterSpacing = spec.trackingEm
    includeFontPadding = false
    applyLineHeight(ctx.spPx(spec.lineHeightSp).roundToInt())
}

/** Every line [lineHeightPx] tall with the text centred in it (M3 `LineHeightStyle(Center, Trim.None)`) */
internal fun TextView.applyLineHeight(lineHeightPx: Int) {
    // Start from no vertical padding: the baseline helpers below set it (and leave it alone when they cannot)
    setPadding(paddingLeft, 0, paddingRight, 0)
    val fm = paint.fontMetricsInt
    val fontHeight = fm.descent - fm.ascent
    val extra = lineHeightPx - fontHeight
    if (lineHeightPx > 0) TextViewCompat.setLineHeight(this, lineHeightPx)
    TextViewCompat.setFirstBaselineToTopHeight(this, max(0, -fm.ascent + extra / 2))
    TextViewCompat.setLastBaselineToBottomHeight(this, max(0, fm.descent + extra - extra / 2))
}

/** Height in px of [lines] lines of [spec] (what a caption of that style measures) */
internal fun Context.linesHeightPx(
    spec: TextSpec,
    lines: Int,
): Int = lines * spPx(spec.lineHeightSp).roundToInt()

/** `itemKindLabel` (`ui/components/HeaderUtils.kt`): the card type badge text */
internal fun itemKindLabel(
    context: Context,
    kind: BaseItemKind?,
): String? =
    when (kind) {
        BaseItemKind.MOVIE -> context.getString(R.string.kind_movie)
        BaseItemKind.SERIES -> context.getString(R.string.show)
        BaseItemKind.SEASON -> context.getString(R.string.kind_season)
        BaseItemKind.EPISODE -> context.getString(R.string.kind_episode)
        BaseItemKind.BOX_SET -> context.getString(R.string.collection)
        BaseItemKind.PLAYLIST -> context.getString(R.string.playlist)
        else -> null
    }

/** `isForwardButton` (KeyIdentifier.kt) on native key codes: page down, channel down, next, fast forward, skip forward */
internal fun isForwardKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
        keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
        keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD

/** `isBackwardButton` on native key codes */
internal fun isBackwardKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
        keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD

/** `isPlayKeyUp`: media play or play/pause released */
internal fun isPlayKeyUp(event: KeyEvent): Boolean =
    event.action == KeyEvent.ACTION_UP &&
        (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
