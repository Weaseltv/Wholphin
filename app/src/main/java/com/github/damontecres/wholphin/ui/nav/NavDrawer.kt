package com.github.damontecres.wholphin.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonDrawerItemColors
import com.github.damontecres.wholphin.ui.theme.neonIconGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemBorder
import com.github.damontecres.wholphin.ui.theme.neonListItemGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemShape
import com.github.damontecres.wholphin.ui.theme.sectionAccent
import org.jellyfin.sdk.model.api.CollectionType

/*
 * LEGACY (no-Compose rewrite, 2026-11-22): the side menu is now the View `leanback.shell.SideMenuView`. Only the
 * Compose rail row ([NavItem]) is left here, because the old Compose ThemePreview still draws it. Safe to delete
 * together with ThemePreview at integration (and NavigationDrawerAndroid.kt with it).
 */

/** Owner's poster border values captured from all eight Alpha rows on 2026-10-07. */
private val RailFocusBorderWidth = ApprovedHomeLayout.CARD_APPEARANCE.borderWidthDp.dp
private val RailFocusBorderOpacity = ApprovedHomeLayout.CARD_APPEARANCE.borderOpacityPercent / 100f
private val RailFocusGlowSpread = ApprovedHomeLayout.CARD_APPEARANCE.glowSpreadDp.dp
private val RailFocusGlowOpacity = ApprovedHomeLayout.CARD_APPEARANCE.glowOpacityPercent / 100f

/** Rail rows don't grow when focused, so the room only has to cover their glow. */
internal val RailGlowRoom = maxOf(NeonBoard.GlowSpec.RowFocus, RailFocusGlowSpread) + RailFocusBorderWidth / 2

/** Keep the full stroke and side glow inside the rail without moving its content. */
private object RailItemShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val inset = with(density) { RailGlowRoom.toPx() }.coerceAtMost(size.width / 4f)
        return Outline.Rectangle(Rect(inset, 0f, size.width - inset, size.height))
    }
}

@Composable
fun NavigationDrawerScope.NavItem(
    library: NavDrawerItem,
    onClick: () -> Unit,
    selected: Boolean,
    moreExpanded: Boolean,
    drawerOpen: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    containerColor: Color = Color.Unspecified,
) {
    val requestsVM: com.github.damontecres.wholphin.ui.discover.SeerrRequestsViewModel =
        androidx.hilt.lifecycle.viewmodel.compose
            .hiltViewModel()
    val requestState by requestsVM.requests.state.collectAsState()
    val context = LocalContext.current
    val icon =
        remember(library) {
            when (library) {
                NavDrawerItem.Favorites -> {
                    R.string.fa_heart
                }

                NavDrawerItem.More -> {
                    R.string.fa_ellipsis
                }

                NavDrawerItem.Discover -> {
                    R.string.fa_magnifying_glass_plus
                }

                is ServerNavDrawerItem -> {
                    when (library.type) {
                        CollectionType.MOVIES -> R.string.fa_film
                        CollectionType.TVSHOWS -> R.string.fa_tv
                        CollectionType.HOMEVIDEOS -> R.string.fa_video
                        CollectionType.BOXSETS -> R.string.fa_open_folder
                        CollectionType.PLAYLISTS -> R.string.fa_list_ul
                        else -> R.string.fa_film
                    }
                }
            }
        }
    // Neon Board: the section accent — volt for the built-ins, the library's type color
    // (movies orange, shows yellow, Boxing / UFC cyan by name).
    val accent = remember(library) { sectionAccent(library) }
    val focused by interactionSource.collectIsFocusedAsState()
    NavigationDrawerItem(
        modifier = modifier,
        selected = false,
        onClick = onClick,
        shape = neonListItemShape(shape = RailItemShape),
        colors = neonDrawerItemColors(accent, containerColor),
        border = neonListItemBorder(accent, shape = RailItemShape, width = RailFocusBorderWidth, opacity = RailFocusBorderOpacity),
        glow = neonListItemGlow(accent, spread = RailFocusGlowSpread, opacity = RailFocusGlowOpacity),
        leadingContent = {
            val color = railGlyphColor(accent, selected, focused) ?: navItemColor(selected, focused, drawerOpen)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .neonIconGlow(accent, selected),
                contentAlignment = Alignment.Center,
            ) {
                // WeaselFin ships its own drawable set; null on every upstream flavor, which
                // falls through to the Font Awesome glyph exactly as before.
                val weasel = remember(library) { WeaselNavIcons.navIconFor(context, library) }
                if (weasel != null) {
                    Icon(
                        painter = painterResource(weasel),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(DrawerIconSize),
                    )
                } else {
                    Text(
                        text = stringResource(icon),
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp,
                        fontFamily = FontAwesome,
                        color = color,
                        modifier = Modifier,
                    )
                }
                if (library is NavDrawerItem.Discover && requestState.badgeCount > 0) {
                    // The badge sits above the glyph, within the row, on both the open menu and collapsed rail.
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-12).dp)
                                .height(12.dp)
                                .widthIn(min = 12.dp)
                                .background(Color(0xFF00F2D6), androidx.compose.foundation.shape.CircleShape)
                                .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (requestState.badgeCount > 9) "9+" else requestState.badgeCount.toString(),
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            color = Color.Black,
                        )
                    }
                }
            }
        },
        trailingContent =
            if (library is NavDrawerItem.More) {
                {
                    Icon(
                        imageVector = if (moreExpanded) Icons.Default.ArrowDropDown else Icons.Default.KeyboardArrowLeft,
                        contentDescription = null,
                    )
                }
            } else {
                null
            },
        interactionSource = interactionSource,
    ) {
        Text(
            modifier = Modifier,
            text = library.name(context),
            maxLines = 1,
            style = railLabelStyle(accent, selected, focused),
        )
    }
}

/** The active page remains visible when focus moves to a different sidebar button. */
@Composable
private fun railLabelStyle(
    accent: Color,
    selected: Boolean,
    focused: Boolean,
): androidx.compose.ui.text.TextStyle {
    if (!isWeaselTv()) return LocalTextStyle.current
    val style = NeonType.railLabel(selected)
    return if (selected && !focused) {
        style.copy(color = accent)
    } else {
        style.copy(color = if (focused) NeonBoard.Text else Color(0xFFE1E4EC))
    }
}

/**
 * Neon Board rail glyphs: `low` at rest, `text` when focused, the section accent when
 * selected. Null on every other theme so [navItemColor] runs unchanged for them.
 */
@Composable
@ReadOnlyComposable
fun railGlyphColor(
    accent: Color,
    selected: Boolean,
    focused: Boolean,
): Color? {
    if (!isWeaselTv()) return null
    return com.github.damontecres.wholphin.ui.theme
        .neonGlowAccent(accent)
}

@Composable
@ReadOnlyComposable
fun navItemColor(
    selected: Boolean,
    focused: Boolean,
    drawerOpen: Boolean,
): Color {
    val theme = LocalTheme.current
    if (theme == AppThemeColors.OLED_BLACK) {
        return when {
            selected && focused -> Color.Black
            selected && !drawerOpen -> Color.White.copy(alpha = .5f)
            selected && drawerOpen -> Color.White.copy(alpha = .85f)
            focused -> Color.Black.copy(alpha = .5f)
            drawerOpen -> Color(0xFF707070)
            else -> Color(0xFF505050).copy(alpha = .66f)
        }
    } else {
        val alpha =
            when {
                drawerOpen -> .85f
                selected && !drawerOpen -> .5f
                else -> .2f
            }
        return when {
            selected && focused -> {
                when (theme) {
                    AppThemeColors.UNRECOGNIZED,
                    AppThemeColors.PURPLE,
                    AppThemeColors.BLUE,
                    AppThemeColors.GREEN,
                    AppThemeColors.ORANGE,
                    AppThemeColors.RED,
                    AppThemeColors.BROWN,
                    AppThemeColors.WEASELTV,
                    -> MaterialTheme.colorScheme.border

                    AppThemeColors.BOLD_BLUE,
                    AppThemeColors.OLED_BLACK,
                    -> MaterialTheme.colorScheme.primary
                }
            }

            selected -> {
                MaterialTheme.colorScheme.border
            }

            focused -> {
                LocalContentColor.current
            }

            else -> {
                MaterialTheme.colorScheme.onSurface
            }
        }.copy(alpha = alpha)
    }
}
