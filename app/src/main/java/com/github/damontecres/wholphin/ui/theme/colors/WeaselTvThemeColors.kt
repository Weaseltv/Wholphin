package com.github.damontecres.wholphin.ui.theme.colors

import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.ui.theme.ThemeColors

/**
 * WeaselTV "Signal Graphite" — the WeaselFin brand theme.
 *
 * Values are taken verbatim from the approved design handoff (`theme-tokens.json`);
 * none are invented here. The animated prismatic rainbow that pairs with this palette
 * lives in `ui/theme/Prismatic.kt`.
 *
 * The TV app always renders dark, so the light schemes deliberately mirror the dark
 * ones rather than inventing a light palette that would never be seen — and that, if
 * it ever were, would put unreadable neon on white.
 */
object WeaselTvColors {
    val Background = Color(0xFF0A0C12)
    val Panel = Color(0xFF101927)
    val PanelDeep = Color(0xFF0B111B)
    val Hairline = Color(0xFF1F2737)
    val Scrim = Color(0xF0020408)

    val TextPrimary = Color(0xFFEAF2FF)
    val TextMuted = Color(0xFF8FA2BD)

    /** Dark ink for use on any bright neon or rainbow fill. Never white — it vanishes. */
    val OnNeon = Color(0xFF02131A)

    val NeonCyan = Color(0xFF00F0FF)
    val StarYellow = Color(0xFFFFF700)
    val FavoriteHeart = Color(0xFFFF3B55)
    val LiveGreen = Color(0xFF39FF14)
}

val WeaselTvThemeColors =
    object : ThemeColors {
        private val background = WeaselTvColors.Background
        private val panel = WeaselTvColors.Panel
        private val panelDeep = WeaselTvColors.PanelDeep
        private val hairline = WeaselTvColors.Hairline
        private val text = WeaselTvColors.TextPrimary
        private val textMuted = WeaselTvColors.TextMuted
        private val neon = WeaselTvColors.NeonCyan
        private val onNeon = WeaselTvColors.OnNeon

        private val errorDark = WeaselTvColors.FavoriteHeart
        private val onErrorDark = onNeon
        private val errorContainerDark = Color(0xFF5C1622)
        private val onErrorContainerDark = Color(0xFFFFDAD6)

        private fun scheme() =
            darkColorScheme(
                primary = neon,
                onPrimary = onNeon,
                primaryContainer = panel,
                onPrimaryContainer = text,
                secondary = WeaselTvColors.LiveGreen,
                onSecondary = onNeon,
                secondaryContainer = panelDeep,
                onSecondaryContainer = text,
                // Card watch-progress uses tertiary as the static fallback wherever an
                // animated brush cannot be applied, so it must read as the same accent.
                tertiary = neon,
                onTertiary = onNeon,
                tertiaryContainer = panelDeep,
                onTertiaryContainer = text,
                error = errorDark,
                onError = onErrorDark,
                errorContainer = errorContainerDark,
                onErrorContainer = onErrorContainerDark,
                background = background,
                onBackground = text,
                surface = background,
                onSurface = text,
                surfaceVariant = panel,
                onSurfaceVariant = textMuted,
                scrim = WeaselTvColors.Scrim,
                inverseSurface = text,
                inverseOnSurface = background,
                inversePrimary = neon,
                // `border` is what the app reaches for on focus. Kept neon so that any
                // surface not yet converted to the animated brush still reads as
                // WeaselTV rather than falling back to another theme's accent.
                border = neon,
            )

        private fun materialScheme() =
            androidx.compose.material3.darkColorScheme(
                primary = neon,
                onPrimary = onNeon,
                primaryContainer = panel,
                onPrimaryContainer = text,
                secondary = WeaselTvColors.LiveGreen,
                onSecondary = onNeon,
                secondaryContainer = panelDeep,
                onSecondaryContainer = text,
                tertiary = neon,
                onTertiary = onNeon,
                tertiaryContainer = panelDeep,
                onTertiaryContainer = text,
                error = errorDark,
                onError = onErrorDark,
                errorContainer = errorContainerDark,
                onErrorContainer = onErrorContainerDark,
                background = background,
                onBackground = text,
                surface = background,
                onSurface = text,
                surfaceVariant = panel,
                onSurfaceVariant = textMuted,
                outline = hairline,
                outlineVariant = hairline,
                scrim = WeaselTvColors.Scrim,
                inverseSurface = text,
                inverseOnSurface = background,
                inversePrimary = neon,
            )

        override val lightSchemeMaterial = materialScheme()
        override val darkSchemeMaterial = materialScheme()
        override val lightScheme = scheme()
        override val darkScheme = scheme()
    }
