package com.github.damontecres.wholphin.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.Glow
import androidx.tv.material3.ListItemBorder
import androidx.tv.material3.ListItemColors
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.ListItemGlow
import androidx.tv.material3.ListItemShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerItemColors
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.time.LocalDateTime

/*
 * WeaselTV "Neon Board" (design handoff 2026-09-16, `01-tokens-tv.md`, `02-components-tv.md`).
 *
 * The fork's rule is unchanged: every helper in this file returns the STOCK tv-material3
 * value unless the WeaselTV theme is active, so a call site can adopt a helper without
 * changing how any upstream theme renders and rebases stay cheap.
 *
 * The one focus recipe: 1dp accent border, accent glow, cards scale 1.06, rows fill with
 * the accent tint. Selected / current is the 3dp tally. Nothing animates on its own.
 */

/** Tokens. Colors are identical to the iOS, Android phone and web packages. */
object NeonBoard {
    val Stage = Color(0xFF050608)
    val Video = Color(0xFF000000)
    val Card = Color(0xFF0B0D12)
    val Card2 = Color(0xFF11141B)
    val Well = Color(0xFF08090D)
    val Line = Color(0xFF1C2029)
    val Line2 = Color(0xFF2A303B)
    val Text = Color(0xFFF2F5F9)
    val Mid = Color(0xFF8B95A5)
    val Low = Color(0xFF6B7686)
    val OnAccent = Color(0xFF050608)

    val Volt = Color(0xFFD4F63F)
    val Green = Color(0xFF39FF14)
    val Cyan = Color(0xFF00F0FF)
    val Orange = Color(0xFFFF7A00)
    val Yellow = Color(0xFFFFD400)
    val Red = Color(0xFFFF3B4E)
    val Warn = Color(0xFFF5B93D)

    /** Player buttons over video. */
    val Glass = Color(0xB8050608)

    /** Behind dialogs and the options menu. */
    val Scrim = Color(0xAD050608)

    /** Selected chips and the focused row fill: accent 14 % over [Card]. */
    fun chipOn(accent: Color): Color = accent.copy(alpha = .14f).compositeOver(Card)

    /** The current guide block: accent 9 % over [Stage]. */
    fun nowFill(accent: Color): Color = accent.copy(alpha = .09f).compositeOver(Stage)

    /** Sizes in dp for the 1080p reference frame (960×540 dp). */
    object Size {
        val OverscanX = 48.dp
        val OverscanY = 27.dp
        val RailCollapsed = 72.dp
        val RailExpanded = 240.dp
        val RailRow = 44.dp
        val RailGlyph = 24.dp
        val RailAvatar = 36.dp
        val Tally = 3.dp
        val Rule = 1.dp
        val ProgressCard = 3.dp
        val Button = 48.dp
        val ButtonHero = 52.dp
        val Chip = 36.dp
        val Tab = 40.dp
        val RowSettings = 60.dp
        val RowMenu = 52.dp
        val RowGuide = 56.dp
        val UserTile = 120.dp
        val DialogWidth = 448.dp
        val DialogButtonRow = 52.dp
        val OptionsMenuWidth = 340.dp
        val SeekBar = 4.dp
        val SeekThumb = 14.dp
        val ScreensaverMark = 96.dp
    }

    /** Glow elevations and alphas from `01-tokens-tv.md` § Glow. */
    object GlowSpec {
        val CardFocus = 22.dp
        const val CARD_FOCUS_ALPHA = .5f
        val RowFocus = 16.dp
        const val ROW_FOCUS_ALPHA = .45f
        val PrimaryButton = 20.dp
        const val PRIMARY_BUTTON_ALPHA = .4f
        val PrimaryButtonFocus = 28.dp
        const val PRIMARY_BUTTON_FOCUS_ALPHA = .62f
        val Rule = 12.dp
        const val RULE_ALPHA = .8f
        val Tally = 12.dp
        val Badge = 12.dp
        const val BADGE_ALPHA = .3f
        const val BADGE_FILLED_ALPHA = .5f
        val Progress = 8.dp
        const val PROGRESS_ALPHA = .8f
        val Icon = 7.dp
        const val ICON_ALPHA = .85f
    }

    /** Focus transition length in ms; the only motion that focus produces. */
    const val FOCUS_MS = 120
    const val DISABLED_ALPHA = .45f
}

@Composable
@ReadOnlyComposable
fun isWeaselTv(): Boolean = LocalTheme.current == AppThemeColors.WEASELTV

/**
 * The accent for the section of the app currently on screen: the rail item, the page
 * chrome (rules, tabs, chips, settings). Defaults to volt, the Home / utility color.
 *
 * Provided per page with [ProvideNeonAccent]; components that do not know their item
 * read this so a Button on a movie page is orange without being told.
 */
val LocalNeonAccent = compositionLocalOf { NeonBoard.Volt }

@Composable
fun ProvideNeonAccent(
    accent: Color,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalNeonAccent provides accent, content = content)

/**
 * Owner ruling 2026-09-17: every rail item gets its OWN neon; only the 4K libraries share
 * the colour of their regular movie / show counterparts. Built-ins match on id, libraries
 * on name; anything unnamed falls back to its collection type.
 */
private val RAIL_ACCENT_BY_ID =
    mapOf(
        "a_favorites" to Color(0xFF5268FF), // hyper blue
        "a_discover" to Color(0xFF00FF8A), // spring mint (Requests)
        "a_more" to NeonBoard.Volt,
    )
private val RAIL_ACCENT_BY_NAME =
    mapOf(
        "stand up comedy" to Color(0xFFFF2EF7), // magenta
        "boxing" to Color(0xFFFF2D95), // hot pink
        "ufc" to Color(0xFF00A3FF), // azure
    )

/** Fixed rail items: Search cyan, Home volt, Settings violet, Now playing green. */
object RailAccents {
    val Search = NeonBoard.Cyan
    val Home = NeonBoard.Volt
    val Settings = Color(0xFFC026FF)
    val NowPlaying = NeonBoard.Green
}

/** Section accent for a nav rail item: its own neon, or its collection type's colour. */
fun sectionAccent(item: NavDrawerItem): Color =
    when (item) {
        is ServerNavDrawerItem -> {
            RAIL_ACCENT_BY_NAME[item.name.trim().lowercase()] ?: collectionAccent(item.type)
        }

        else -> {
            RAIL_ACCENT_BY_ID[item.id] ?: NeonBoard.Volt
        }
    }

/** Section accent for a library by its Jellyfin collection type. */
fun collectionAccent(type: CollectionType?): Color =
    when (type) {
        CollectionType.MOVIES -> NeonBoard.Orange
        CollectionType.TVSHOWS -> NeonBoard.Yellow
        CollectionType.LIVETV -> NeonBoard.Green
        else -> NeonBoard.Volt
    }

/** Type accent for an item: what its card, Play button, progress and chapters wear. */
fun typeAccent(kind: BaseItemKind?): Color =
    when (kind) {
        BaseItemKind.MOVIE -> NeonBoard.Orange

        BaseItemKind.SERIES,
        BaseItemKind.SEASON,
        BaseItemKind.EPISODE,
        -> NeonBoard.Yellow

        BaseItemKind.TV_CHANNEL,
        BaseItemKind.LIVE_TV_CHANNEL,
        BaseItemKind.CHANNEL,
        BaseItemKind.RECORDING,
        -> NeonBoard.Green

        BaseItemKind.TV_PROGRAM,
        BaseItemKind.LIVE_TV_PROGRAM,
        BaseItemKind.PROGRAM,
        -> NeonBoard.Cyan

        else -> NeonBoard.Volt
    }

/** A programme that is on air now is live (green); one that is scheduled is cyan. */
fun itemAccent(item: BaseItem?): Color {
    if (item == null) return NeonBoard.Volt
    val kind = item.type
    val isProgram =
        kind == BaseItemKind.TV_PROGRAM || kind == BaseItemKind.LIVE_TV_PROGRAM || kind == BaseItemKind.PROGRAM
    if (isProgram) {
        val now = LocalDateTime.now()
        val start = item.data.startDate
        val end = item.data.endDate
        if (start != null && end != null && now.isAfter(start) && now.isBefore(end)) return NeonBoard.Green
    }
    return typeAccent(kind)
}

/** Accent for an item when the theme is active, otherwise the page accent. */
@Composable
@ReadOnlyComposable
fun neonAccentFor(item: BaseItem?): Color = if (item == null) LocalNeonAccent.current else itemAccent(item)

// ---------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------

/** Rest: no border. Focused: 1dp accent. */
@Composable
fun neonCardBorder(
    accent: Color = LocalNeonAccent.current,
    shape: Shape = RectangleShape,
    fallback: CardBorder? = null,
): CardBorder {
    if (!isWeaselTv()) return fallback ?: CardDefaults.border()
    val focused = Border(border = BorderStroke(1.dp, accent), shape = shape)
    return CardDefaults.border(
        border = Border.None,
        focusedBorder = focused,
        pressedBorder = focused,
    )
}

/** Card focus glow: 22dp at 50 % in the accent. */
@Composable
fun neonCardGlow(
    accent: Color = LocalNeonAccent.current,
    fallback: CardGlow? = null,
): CardGlow {
    if (!isWeaselTv()) return fallback ?: CardDefaults.glow()
    val glow =
        Glow(
            elevationColor = accent.copy(alpha = NeonBoard.GlowSpec.CARD_FOCUS_ALPHA),
            elevation = NeonBoard.GlowSpec.CardFocus,
        )
    return CardDefaults.glow(focusedGlow = glow, pressedGlow = glow)
}

/** Cards scale 1.06 on focus, 0.97 while pressed. */
@Composable
fun neonCardScale(fallback: CardScale? = null): CardScale {
    if (!isWeaselTv()) return fallback ?: CardDefaults.scale()
    return CardDefaults.scale(focusedScale = 1.06f, pressedScale = .97f)
}

/** Square everything except people avatars. */
@Composable
fun neonCardShape(fallback: CardShape? = null): CardShape {
    if (!isWeaselTv()) return fallback ?: CardDefaults.shape()
    return CardDefaults.shape(RectangleShape)
}

// ---------------------------------------------------------------------------------------
// Clickable surfaces (buttons, tiles)
// ---------------------------------------------------------------------------------------

/**
 * Surface border. [restWidth] > 0 draws a hairline at rest (outline buttons and chips
 * take 1dp `line2`); posters and glass buttons pass 0.
 */
@Composable
fun neonSurfaceBorder(
    accent: Color = LocalNeonAccent.current,
    restWidth: Dp = 0.dp,
    restColor: Color = NeonBoard.Line2,
    fallback: ClickableSurfaceBorder? = null,
): ClickableSurfaceBorder {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.border()
    val rest =
        if (restWidth > 0.dp) {
            Border(border = BorderStroke(restWidth, restColor), shape = RectangleShape)
        } else {
            Border.None
        }
    val focused = Border(border = BorderStroke(1.dp, accent), shape = RectangleShape)
    val disabled =
        if (restWidth > 0.dp) {
            Border(
                border = BorderStroke(restWidth, restColor.copy(alpha = NeonBoard.DISABLED_ALPHA)),
                shape = RectangleShape,
            )
        } else {
            Border.None
        }
    return ClickableSurfaceDefaults.border(
        border = rest,
        focusedBorder = focused,
        pressedBorder = focused,
        disabledBorder = disabled,
        focusedDisabledBorder = focused,
    )
}

/** Row and button focus glow: 16dp at 45 %. */
@Composable
fun neonSurfaceGlow(
    accent: Color = LocalNeonAccent.current,
    elevation: Dp = NeonBoard.GlowSpec.RowFocus,
    alpha: Float = NeonBoard.GlowSpec.ROW_FOCUS_ALPHA,
    fallback: ClickableSurfaceGlow? = null,
): ClickableSurfaceGlow {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.glow()
    val glow = Glow(elevationColor = accent.copy(alpha = alpha), elevation = elevation)
    return ClickableSurfaceDefaults.glow(focusedGlow = glow, pressedGlow = glow)
}

@Composable
fun neonSurfaceShape(fallback: ClickableSurfaceShape? = null): ClickableSurfaceShape {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.shape()
    return ClickableSurfaceDefaults.shape(RectangleShape)
}

/** Rows and buttons do not scale; tiles (user, server) pass [focusedScale] 1.06. */
@Composable
fun neonSurfaceScale(
    focusedScale: Float = 1f,
    fallback: ClickableSurfaceScale? = null,
): ClickableSurfaceScale {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.scale()
    return ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = .97f)
}

/**
 * Outline surface colors: transparent at rest, `chipOn` when focused, label `text` at
 * rest and the accent when focused.
 */
@Composable
fun neonOutlineColors(
    accent: Color = LocalNeonAccent.current,
    fallback: ClickableSurfaceColors? = null,
): ClickableSurfaceColors {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.colors()
    return ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = NeonBoard.Text,
        focusedContainerColor = NeonBoard.chipOn(accent),
        focusedContentColor = accent,
        pressedContainerColor = NeonBoard.chipOn(accent),
        pressedContentColor = accent,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = NeonBoard.Text.copy(alpha = NeonBoard.DISABLED_ALPHA),
    )
}

/** Primary surface colors: accent fill with `onAccent` ink in every state. */
@Composable
fun neonPrimaryColors(
    accent: Color = LocalNeonAccent.current,
    fallback: ClickableSurfaceColors? = null,
): ClickableSurfaceColors {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.colors()
    return ClickableSurfaceDefaults.colors(
        containerColor = accent,
        contentColor = NeonBoard.OnAccent,
        focusedContainerColor = accent,
        focusedContentColor = NeonBoard.OnAccent,
        pressedContainerColor = accent,
        pressedContentColor = NeonBoard.OnAccent,
        disabledContainerColor = accent.copy(alpha = NeonBoard.DISABLED_ALPHA),
        disabledContentColor = NeonBoard.OnAccent.copy(alpha = NeonBoard.DISABLED_ALPHA),
    )
}

/** Primary buttons carry a 2dp `text` ring when focused so focus is unambiguous on a fill. */
@Composable
fun neonPrimaryBorder(fallback: ClickableSurfaceBorder? = null): ClickableSurfaceBorder {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.border()
    val focused = Border(border = BorderStroke(2.dp, NeonBoard.Text), shape = RectangleShape)
    return ClickableSurfaceDefaults.border(
        border = Border.None,
        focusedBorder = focused,
        pressedBorder = focused,
        disabledBorder = Border.None,
        focusedDisabledBorder = focused,
    )
}

/** Primary buttons glow at rest (20dp at 40 %) and harder when focused (28dp at 62 %). */
@Composable
fun neonPrimaryGlow(
    accent: Color = LocalNeonAccent.current,
    fallback: ClickableSurfaceGlow? = null,
): ClickableSurfaceGlow {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.glow()
    val focused =
        Glow(
            elevationColor = accent.copy(alpha = NeonBoard.GlowSpec.PRIMARY_BUTTON_FOCUS_ALPHA),
            elevation = NeonBoard.GlowSpec.PrimaryButtonFocus,
        )
    return ClickableSurfaceDefaults.glow(
        glow =
            Glow(
                elevationColor = accent.copy(alpha = NeonBoard.GlowSpec.PRIMARY_BUTTON_ALPHA),
                elevation = NeonBoard.GlowSpec.PrimaryButton,
            ),
        focusedGlow = focused,
        pressedGlow = focused,
    )
}

/** Glass (icon-only over video) colors: `glass` fill, `text` glyph, accent when focused. */
@Composable
fun neonGlassColors(
    accent: Color = LocalNeonAccent.current,
    fallback: ClickableSurfaceColors? = null,
): ClickableSurfaceColors {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.colors()
    return ClickableSurfaceDefaults.colors(
        containerColor = NeonBoard.Glass,
        contentColor = NeonBoard.Text,
        focusedContainerColor = NeonBoard.Glass,
        focusedContentColor = accent,
        pressedContainerColor = NeonBoard.Glass,
        pressedContentColor = accent,
        disabledContainerColor = NeonBoard.Glass,
        disabledContentColor = NeonBoard.Text.copy(alpha = NeonBoard.DISABLED_ALPHA),
    )
}

// ---------------------------------------------------------------------------------------
// List items (settings rows, menus, dialogs)
// ---------------------------------------------------------------------------------------

@Composable
fun neonListItemBorder(
    accent: Color = LocalNeonAccent.current,
    fallback: ListItemBorder? = null,
): ListItemBorder {
    if (!isWeaselTv()) return fallback ?: ListItemDefaults.border()
    val focused = Border(border = BorderStroke(1.dp, accent), shape = RectangleShape)
    return ListItemDefaults.border(
        focusedBorder = focused,
        pressedBorder = focused,
        focusedSelectedBorder = focused,
        pressedSelectedBorder = focused,
    )
}

@Composable
fun neonListItemGlow(
    accent: Color = LocalNeonAccent.current,
    fallback: ListItemGlow? = null,
): ListItemGlow {
    if (!isWeaselTv()) return fallback ?: ListItemDefaults.glow()
    val glow =
        Glow(
            elevationColor = accent.copy(alpha = NeonBoard.GlowSpec.ROW_FOCUS_ALPHA),
            elevation = NeonBoard.GlowSpec.RowFocus,
        )
    return ListItemDefaults.glow(focusedGlow = glow, pressedGlow = glow, focusedSelectedGlow = glow)
}

@Composable
fun neonListItemShape(fallback: ListItemShape? = null): ListItemShape {
    if (!isWeaselTv()) return fallback ?: ListItemDefaults.shape()
    return ListItemDefaults.shape(RectangleShape)
}

/** Rows: transparent at rest, `chipOn` when focused, labels `text`. */
@Composable
fun neonListItemColors(
    accent: Color = LocalNeonAccent.current,
    fallback: ListItemColors? = null,
): ListItemColors {
    if (!isWeaselTv()) return fallback ?: ListItemDefaults.colors()
    val fill = NeonBoard.chipOn(accent)
    return ListItemDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = NeonBoard.Text,
        focusedContainerColor = fill,
        focusedContentColor = NeonBoard.Text,
        pressedContainerColor = fill,
        pressedContentColor = NeonBoard.Text,
        selectedContainerColor = Color.Transparent,
        selectedContentColor = accent,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = NeonBoard.Text.copy(alpha = NeonBoard.DISABLED_ALPHA),
        focusedSelectedContainerColor = fill,
        focusedSelectedContentColor = accent,
        pressedSelectedContainerColor = fill,
        pressedSelectedContentColor = accent,
    )
}

// ---------------------------------------------------------------------------------------
// Nav rail rows
// ---------------------------------------------------------------------------------------

/**
 * Rail rows: `stage` at rest, `chipOn` when focused; the glyph and label take the
 * section accent when selected and `text` when focused.
 */
@Composable
fun neonDrawerItemColors(
    accent: Color = LocalNeonAccent.current,
    containerColor: Color = Color.Unspecified,
    fallback: NavigationDrawerItemColors? = null,
): NavigationDrawerItemColors {
    if (!isWeaselTv()) return fallback ?: NavigationDrawerItemDefaults.colors(containerColor = containerColor)
    val fill = NeonBoard.chipOn(accent)
    return NavigationDrawerItemDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = NeonBoard.Low,
        focusedContainerColor = fill,
        focusedContentColor = NeonBoard.Text,
        pressedContainerColor = fill,
        pressedContentColor = NeonBoard.Text,
        selectedContainerColor = Color.Transparent,
        selectedContentColor = accent,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = NeonBoard.Low.copy(alpha = NeonBoard.DISABLED_ALPHA),
        focusedSelectedContainerColor = fill,
        focusedSelectedContentColor = accent,
        pressedSelectedContainerColor = fill,
        pressedSelectedContentColor = accent,
    )
}

// ---------------------------------------------------------------------------------------
// Modifiers
// ---------------------------------------------------------------------------------------

/**
 * The selected / current marker: a 3dp bar on the leading edge in the accent with a
 * 12dp glow. The bar is drawn INSIDE the bounds so layouts do not move when it appears.
 */
@Composable
fun Modifier.neonTally(
    accent: Color = LocalNeonAccent.current,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || !isWeaselTv()) return this
    return drawBehind {
        val w = NeonBoard.Size.Tally.toPx()
        val glowW = NeonBoard.GlowSpec.Tally.toPx()
        drawRect(
            brush =
                Brush.horizontalGradient(
                    colors = listOf(accent.copy(alpha = .45f), Color.Transparent),
                    startX = 0f,
                    endX = glowW,
                ),
            topLeft = Offset.Zero,
            size = Size(glowW, size.height),
        )
        drawRect(color = accent, topLeft = Offset.Zero, size = Size(w, size.height))
    }
}

/** The glow behind a selected rail glyph: a soft radial in the accent, no blur filter. */
@Composable
fun Modifier.neonIconGlow(
    accent: Color = LocalNeonAccent.current,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || !isWeaselTv()) return this
    return drawBehind {
        val r = size.minDimension * .95f
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            accent.copy(alpha = NeonBoard.GlowSpec.ICON_ALPHA * .5f),
                            accent.copy(alpha = .12f),
                            Color.Transparent,
                        ),
                    center = center,
                    radius = r,
                ),
            radius = r,
        )
    }
}

/** `chipOn` fill while focused, for rows that are not tv-material surfaces. */
@Composable
fun Modifier.neonFocusFill(
    accent: Color = LocalNeonAccent.current,
    focused: Boolean,
): Modifier {
    if (!focused || !isWeaselTv()) return this
    return background(NeonBoard.chipOn(accent))
}

/** A 1dp rule in the accent with its 12dp glow, drawn along the bottom edge. */
@Composable
fun Modifier.neonRuleBelow(
    accent: Color = LocalNeonAccent.current,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || !isWeaselTv()) return this
    return drawBehind {
        val h = NeonBoard.Size.Rule.toPx()
        val glowH = NeonBoard.GlowSpec.Rule.toPx()
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, accent.copy(alpha = .35f)),
                    startY = size.height - glowH,
                    endY = size.height,
                ),
            topLeft = Offset(0f, size.height - glowH),
            size = Size(size.width, glowH),
        )
        drawRect(color = accent, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
    }
}

/** The selected tab's 2dp overline in the accent with its glow, drawn along the top edge. */
@Composable
fun Modifier.neonOverline(
    accent: Color = LocalNeonAccent.current,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || !isWeaselTv()) return this
    return drawBehind {
        val h = 2.dp.toPx()
        val glowH = NeonBoard.GlowSpec.Rule.toPx()
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = .35f), Color.Transparent),
                    startY = 0f,
                    endY = glowH,
                ),
            topLeft = Offset.Zero,
            size = Size(size.width, glowH),
        )
        drawRect(color = accent, topLeft = Offset.Zero, size = Size(size.width, h))
    }
}

/** A progress bar in the accent with an 8dp glow, for cards and rows. */
@Composable
fun Modifier.neonProgress(
    accent: Color = LocalNeonAccent.current,
    enabled: Boolean = true,
): Modifier {
    if (!enabled || !isWeaselTv()) return this
    return drawBehind {
        val glowH = NeonBoard.GlowSpec.Progress.toPx()
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = .4f), Color.Transparent),
                    startY = 0f,
                    endY = -glowH,
                ),
            topLeft = Offset(0f, -glowH),
            size = Size(size.width, glowH),
        )
        drawRect(color = accent)
    }
}

// ---------------------------------------------------------------------------------------
// Small composables shared across pages
// ---------------------------------------------------------------------------------------

/** The section rule under a row or page title. Only rendered on the WeaselTV theme. */
@Composable
fun NeonRule(
    modifier: Modifier = Modifier,
    accent: Color = LocalNeonAccent.current,
) {
    if (!isWeaselTv()) return
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(NeonBoard.Size.Rule)
                .neonRuleBelow(accent),
    )
}

/**
 * The outline / filled badge: 11–12sp Barlow 700 uppercase, 3–4 / 9 padding, 12dp glow.
 * Only rendered on the WeaselTV theme; callers keep their stock overlay for other themes.
 */
@Composable
fun NeonBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = LocalNeonAccent.current,
    filled: Boolean = false,
) {
    if (!isWeaselTv()) return
    val fill = if (filled) accent else NeonBoard.Card.copy(alpha = .72f)
    val ink = if (filled) NeonBoard.OnAccent else accent
    Box(
        modifier =
            modifier
                .drawBehind {
                    val g = NeonBoard.GlowSpec.Badge.toPx()
                    val alpha = if (filled) NeonBoard.GlowSpec.BADGE_FILLED_ALPHA else NeonBoard.GlowSpec.BADGE_ALPHA
                    drawRect(
                        brush =
                            Brush.radialGradient(
                                colors = listOf(accent.copy(alpha = alpha * .5f), Color.Transparent),
                                center = center,
                                radius = size.maxDimension / 2 + g,
                            ),
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                    )
                    drawRect(color = fill)
                    drawRect(
                        color = accent,
                        style =
                            androidx.compose.ui.graphics.drawscope
                                .Stroke(width = 1.dp.toPx()),
                    )
                }.padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = ink,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** Eyebrow: 12sp Barlow 700 uppercase, tracking 0.16em, in the accent. */
@Composable
fun NeonEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = LocalNeonAccent.current,
) {
    Text(
        text = text.uppercase(),
        color = accent,
        style = NeonType.eyebrow(),
        maxLines = 1,
        modifier = modifier,
    )
}
