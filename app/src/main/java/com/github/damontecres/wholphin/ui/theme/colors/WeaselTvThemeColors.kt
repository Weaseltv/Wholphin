package com.github.damontecres.wholphin.ui.theme.colors

import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.ThemeColors

/**
 * WeaselTV "Neon Board" — the WeaselPlex brand theme, shared with the iOS app, the
 * Android phone app and the website (design handoff 2026-09-16).
 *
 * Tokens live in [NeonBoard]; this only maps them onto the tv-material3 and Material3
 * color schemes. The enum value stays `WEASELTV` because it is the stored preference;
 * the flavor's strings label it "WeaselPlex".
 *
 * `border` is the stock focus border color. Every focusable surface overrides it with
 * its section or type accent through the `neon*` helpers, so it is the quiet `line2`
 * hairline rather than a neon, which keeps any surface not yet converted from shouting.
 *
 * The TV app always renders dark, so the light schemes deliberately mirror the dark ones.
 */
val WeaselTvThemeColors =
    object : ThemeColors {
        private val stage = NeonBoard.Stage
        private val card = NeonBoard.Card
        private val card2 = NeonBoard.Card2
        private val text = NeonBoard.Text
        private val mid = NeonBoard.Mid
        private val volt = NeonBoard.Volt
        private val onAccent = NeonBoard.OnAccent

        private val errorContainer = Color(0xFF3A0F15)
        private val onErrorContainer = Color(0xFFFFD9DC)

        private fun scheme() =
            darkColorScheme(
                primary = volt,
                onPrimary = onAccent,
                primaryContainer = card,
                onPrimaryContainer = text,
                secondary = NeonBoard.Green,
                onSecondary = onAccent,
                secondaryContainer = card2,
                onSecondaryContainer = text,
                tertiary = NeonBoard.Orange,
                onTertiary = onAccent,
                tertiaryContainer = card2,
                onTertiaryContainer = text,
                error = NeonBoard.Red,
                onError = onAccent,
                errorContainer = errorContainer,
                onErrorContainer = onErrorContainer,
                background = stage,
                onBackground = text,
                surface = stage,
                onSurface = text,
                surfaceVariant = card,
                onSurfaceVariant = mid,
                scrim = NeonBoard.Scrim,
                inverseSurface = text,
                inverseOnSurface = stage,
                inversePrimary = volt,
                border = NeonBoard.Line2,
                borderVariant = NeonBoard.Line,
            )

        private fun materialScheme() =
            androidx.compose.material3.darkColorScheme(
                primary = volt,
                onPrimary = onAccent,
                primaryContainer = card,
                onPrimaryContainer = text,
                secondary = NeonBoard.Green,
                onSecondary = onAccent,
                secondaryContainer = card2,
                onSecondaryContainer = text,
                tertiary = NeonBoard.Orange,
                onTertiary = onAccent,
                tertiaryContainer = card2,
                onTertiaryContainer = text,
                error = NeonBoard.Red,
                onError = onAccent,
                errorContainer = errorContainer,
                onErrorContainer = onErrorContainer,
                background = stage,
                onBackground = text,
                surface = stage,
                onSurface = text,
                surfaceVariant = card,
                onSurfaceVariant = mid,
                outline = NeonBoard.Line2,
                outlineVariant = NeonBoard.Line,
                scrim = NeonBoard.Scrim,
                inverseSurface = text,
                inverseOnSurface = stage,
                inversePrimary = volt,
            )

        override val lightSchemeMaterial = materialScheme()
        override val darkSchemeMaterial = materialScheme()
        override val lightScheme = scheme()
        override val darkScheme = scheme()
    }
