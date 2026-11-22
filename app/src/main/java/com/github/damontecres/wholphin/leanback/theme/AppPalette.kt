package com.github.damontecres.wholphin.leanback.theme

import com.github.damontecres.wholphin.preferences.AppThemeColors

/**
 * The app colour scheme as plain ARGB ints for Views (no-Compose UI, 2026-11-22).
 *
 * Generated from the dark schemes in the `ui/theme/colors` ThemeColors files (the app always renders dark).
 * Field names match the old tv-material3 `ColorScheme` roles, so `MaterialTheme.colorScheme.onSurface`
 * becomes `AppTheme.palette.onSurface`.
 */
data class AppPalette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val scrim: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val inversePrimary: Int,
    /** tv-material3 focus border colour */
    val border: Int,
    val borderVariant: Int,
)

object AppPalettes {
    /** WeaselTV "Neon Board" (WeaselPlex brand), from `WeaselTvThemeColors` */
    val WeaselTv =
        AppPalette(
            primary = NeonColors.VOLT,
            onPrimary = NeonColors.ON_ACCENT,
            primaryContainer = NeonColors.CARD,
            onPrimaryContainer = NeonColors.TEXT,
            secondary = NeonColors.GREEN,
            onSecondary = NeonColors.ON_ACCENT,
            secondaryContainer = NeonColors.CARD2,
            onSecondaryContainer = NeonColors.TEXT,
            tertiary = NeonSections.Movies.border,
            onTertiary = NeonColors.ON_ACCENT,
            tertiaryContainer = NeonColors.CARD2,
            onTertiaryContainer = NeonColors.TEXT,
            error = NeonColors.RED,
            onError = NeonColors.ON_ACCENT,
            errorContainer = 0xFF3A0F15.toInt(),
            onErrorContainer = 0xFFFFD9DC.toInt(),
            background = NeonColors.STAGE,
            onBackground = NeonColors.TEXT,
            surface = NeonColors.STAGE,
            onSurface = NeonColors.TEXT,
            surfaceVariant = NeonColors.CARD,
            onSurfaceVariant = NeonColors.MID,
            scrim = NeonColors.SCRIM,
            inverseSurface = NeonColors.TEXT,
            inverseOnSurface = NeonColors.STAGE,
            inversePrimary = NeonColors.VOLT,
            border = NeonColors.LINE2,
            borderVariant = NeonColors.LINE,
        )

    fun of(theme: AppThemeColors): AppPalette =
        when (theme) {
            AppThemeColors.WEASELTV -> {
                WeaselTv
            }

            AppThemeColors.PURPLE -> {
                AppPalette(
                    primary = 0xFFD2BCFF.toInt(),
                    onPrimary = 0xFF3D008F.toInt(),
                    primaryContainer = 0xFF7A28FE.toInt(),
                    onPrimaryContainer = 0xFFEADCFF.toInt(),
                    secondary = 0xFFD2BCFF.toInt(),
                    onSecondary = 0xFF3B167D.toInt(),
                    secondaryContainer = 0xFF48415B.toInt(),
                    onSecondaryContainer = 0xFFDFD3F8.toInt(),
                    tertiary = 0xFFA071F8.toInt(),
                    onTertiary = 0xFF5E0052.toInt(),
                    tertiaryContainer = 0xFFB800A3.toInt(),
                    onTertiaryContainer = 0xFFFFD7F0.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFDAD6.toInt(),
                    background = 0xFF15121C.toInt(),
                    onBackground = 0xFFE8DFF0.toInt(),
                    surface = 0xFF15121C.toInt(),
                    onSurface = 0xFFE8DFF0.toInt(),
                    surfaceVariant = 0xFF4A4456.toInt(),
                    onSurfaceVariant = 0xFFCCC3D9.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFE8DFF0.toInt(),
                    inverseOnSurface = 0xFF332E3A.toInt(),
                    inversePrimary = 0xFF741BF8.toInt(),
                    border = 0xBF741BF8.toInt(),
                    borderVariant = 0xFF4A4456.toInt(),
                )
            }

            AppThemeColors.BLUE -> {
                AppPalette(
                    primary = 0xFFAAC7FF.toInt(),
                    onPrimary = 0xFF09305F.toInt(),
                    primaryContainer = 0xFF274777.toInt(),
                    onPrimaryContainer = 0xFFD6E3FF.toInt(),
                    secondary = 0xFFBEC7DC.toInt(),
                    onSecondary = 0xFF283141.toInt(),
                    secondaryContainer = 0xFF3E4759.toInt(),
                    onSecondaryContainer = 0xFFDAE2F9.toInt(),
                    tertiary = 0xFFBECEFF.toInt(),
                    onTertiary = 0xFF31285F.toInt(),
                    tertiaryContainer = 0xFF483F77.toInt(),
                    onTertiaryContainer = 0xFFE6DEFF.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFDAD6.toInt(),
                    background = 0xFF111318.toInt(),
                    onBackground = 0xFFE2E2E9.toInt(),
                    surface = 0xFF121318.toInt(),
                    onSurface = 0xFFE3E2E9.toInt(),
                    surfaceVariant = 0xFF44474F.toInt(),
                    onSurfaceVariant = 0xFFC4C6D0.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFE3E2E9.toInt(),
                    inverseOnSurface = 0xFF2F3036.toInt(),
                    inversePrimary = 0xFF405F90.toInt(),
                    border = 0xFF405F90.toInt(),
                    borderVariant = 0xFF44474F.toInt(),
                )
            }

            AppThemeColors.GREEN -> {
                AppPalette(
                    primary = 0xFF98D5A4.toInt(),
                    onPrimary = 0xFF00391A.toInt(),
                    primaryContainer = 0xFF16512C.toInt(),
                    onPrimaryContainer = 0xFFB3F1BF.toInt(),
                    secondary = 0xFFB7CCB7.toInt(),
                    onSecondary = 0xFF223526.toInt(),
                    secondaryContainer = 0xFF394B3C.toInt(),
                    onSecondaryContainer = 0xFFD2E8D3.toInt(),
                    tertiary = 0xFF81D29C.toInt(),
                    onTertiary = 0xFF01363F.toInt(),
                    tertiaryContainer = 0xFF204D56.toInt(),
                    onTertiaryContainer = 0xFFBDEAF5.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFDAD6.toInt(),
                    background = 0xFF101510.toInt(),
                    onBackground = 0xFFDFE4DC.toInt(),
                    surface = 0xFF101510.toInt(),
                    onSurface = 0xFFDFE4DC.toInt(),
                    surfaceVariant = 0xFF414941.toInt(),
                    onSurfaceVariant = 0xFFC1C9BF.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFDFE4DC.toInt(),
                    inverseOnSurface = 0xFF2D322D.toInt(),
                    inversePrimary = 0xFF39912F.toInt(),
                    border = 0xBF39912F.toInt(),
                    borderVariant = 0xFF414941.toInt(),
                )
            }

            AppThemeColors.ORANGE -> {
                AppPalette(
                    primary = 0xFFB67F12.toInt(),
                    onPrimary = 0xFFE0CB7F.toInt(),
                    primaryContainer = 0xFF574500.toInt(),
                    onPrimaryContainer = 0xFFFFE086.toInt(),
                    secondary = 0xFFD4C5A1.toInt(),
                    onSecondary = 0xFF383016.toInt(),
                    secondaryContainer = 0xFF524127.toInt(),
                    onSecondaryContainer = 0xFFF1E1BB.toInt(),
                    tertiary = 0xFFFCE574.toInt(),
                    onTertiary = 0xFF492900.toInt(),
                    tertiaryContainer = 0xFF693C00.toInt(),
                    onTertiaryContainer = 0xFFFFDCBD.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFDAD6.toInt(),
                    background = 0xFF1C1715.toInt(),
                    onBackground = 0xFFEAE2D4.toInt(),
                    surface = 0xFF1C1715.toInt(),
                    onSurface = 0xFFEAE2D4.toInt(),
                    surfaceVariant = 0xFF4C4639.toInt(),
                    onSurfaceVariant = 0xFFCEC6B4.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFEAE2D4.toInt(),
                    inverseOnSurface = 0xFF343027.toInt(),
                    inversePrimary = 0xFFE39012.toInt(),
                    border = 0xFFE39012.toInt(),
                    borderVariant = 0xFF4C4639.toInt(),
                )
            }

            AppThemeColors.OLED_BLACK -> {
                AppPalette(
                    primary = 0xC04D4B4B.toInt(),
                    onPrimary = 0xFFFFFFFF.toInt(),
                    primaryContainer = 0xFF505050.toInt(),
                    onPrimaryContainer = 0xC0FFFFFF.toInt(),
                    secondary = 0xFF808080.toInt(),
                    onSecondary = 0xC0FFFFFF.toInt(),
                    secondaryContainer = 0xFF505050.toInt(),
                    onSecondaryContainer = 0xC0FFFFFF.toInt(),
                    tertiary = 0xFFDEDEDE.toInt(),
                    onTertiary = 0xFF363535.toInt(),
                    tertiaryContainer = 0xFF909090.toInt(),
                    onTertiaryContainer = 0xFFFFFFFF.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFFFFF.toInt(),
                    background = 0xFF000000.toInt(),
                    onBackground = 0xC0FFFFFF.toInt(),
                    surface = 0xFF000000.toInt(),
                    onSurface = 0xC0FFFFFF.toInt(),
                    surfaceVariant = 0xFF303030.toInt(),
                    onSurfaceVariant = 0xC0FFFFFF.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFE8E8E8.toInt(),
                    inverseOnSurface = 0xFF000000.toInt(),
                    inversePrimary = 0xC0F9F4FF.toInt(),
                    border = 0xC0F9F4FF.toInt(),
                    borderVariant = 0xFF303030.toInt(),
                )
            }

            AppThemeColors.BOLD_BLUE -> {
                AppPalette(
                    primary = 0xFF0C2A67.toInt(),
                    onPrimary = 0xFF558DD0.toInt(),
                    primaryContainer = 0xFF192E4F.toInt(),
                    onPrimaryContainer = 0xFFB4C9F6.toInt(),
                    secondary = 0xFFBEC7DC.toInt(),
                    onSecondary = 0xFF283141.toInt(),
                    secondaryContainer = 0xFF3E4759.toInt(),
                    onSecondaryContainer = 0xFFDAE2F9.toInt(),
                    tertiary = 0xFFBEC9FF.toInt(),
                    onTertiary = 0xFF28345F.toInt(),
                    tertiaryContainer = 0xFF8CDDE8.toInt(),
                    onTertiaryContainer = 0xFFE6DEFF.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFDAD6.toInt(),
                    background = 0xFF111318.toInt(),
                    onBackground = 0xFFE2E2E9.toInt(),
                    surface = 0xFF0A1017.toInt(),
                    onSurface = 0xFFDAE3FA.toInt(),
                    surfaceVariant = 0xFF44474F.toInt(),
                    onSurfaceVariant = 0xFFC4C6D0.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFE3E2E9.toInt(),
                    inverseOnSurface = 0xFF2F3036.toInt(),
                    inversePrimary = 0xFF152986.toInt(),
                    border = 0xFF152986.toInt(),
                    borderVariant = 0xFF44474F.toInt(),
                )
            }

            AppThemeColors.RED -> {
                AppPalette(
                    primary = 0xFFFFB4AA.toInt(),
                    onPrimary = 0xFF561E18.toInt(),
                    primaryContainer = 0xFF73342C.toInt(),
                    onPrimaryContainer = 0xFFFFDAD5.toInt(),
                    secondary = 0xFFE7BDB7.toInt(),
                    onSecondary = 0xFF442926.toInt(),
                    secondaryContainer = 0xFF5D3F3B.toInt(),
                    onSecondaryContainer = 0xFFFFDAD5.toInt(),
                    tertiary = 0xFFDFC38C.toInt(),
                    onTertiary = 0xFF3F2E04.toInt(),
                    tertiaryContainer = 0xFF574419.toInt(),
                    onTertiaryContainer = 0xFFFDDFA6.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFDAD6.toInt(),
                    background = 0xFF1A1110.toInt(),
                    onBackground = 0xFFF1DEDC.toInt(),
                    surface = 0xFF1A1110.toInt(),
                    onSurface = 0xFFF1DEDC.toInt(),
                    surfaceVariant = 0xFF534341.toInt(),
                    onSurfaceVariant = 0xFFD8C2BE.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFF1DEDC.toInt(),
                    inverseOnSurface = 0xFF392E2C.toInt(),
                    inversePrimary = 0xFFC0000C.toInt(),
                    border = 0xFFC0000C.toInt(),
                    borderVariant = 0xFF534341.toInt(),
                )
            }

            AppThemeColors.BROWN -> {
                AppPalette(
                    primary = 0xFFFFB784.toInt(),
                    onPrimary = 0xFF4F2500.toInt(),
                    primaryContainer = 0xFF6D390C.toInt(),
                    onPrimaryContainer = 0xFFFFDCC6.toInt(),
                    secondary = 0xFFE4BFA8.toInt(),
                    onSecondary = 0xFF422B1B.toInt(),
                    secondaryContainer = 0xFF5B412F.toInt(),
                    onSecondaryContainer = 0xFFFFDCC6.toInt(),
                    tertiary = 0xFFC8CA94.toInt(),
                    onTertiary = 0xFF30330B.toInt(),
                    tertiaryContainer = 0xFF47491F.toInt(),
                    onTertiaryContainer = 0xFFE5E6AE.toInt(),
                    error = 0xFFFFB4AB.toInt(),
                    onError = 0xFF690005.toInt(),
                    errorContainer = 0xFF93000A.toInt(),
                    onErrorContainer = 0xFFFFDAD6.toInt(),
                    background = 0xFF19120D.toInt(),
                    onBackground = 0xFFF0DFD6.toInt(),
                    surface = 0xFF19120D.toInt(),
                    onSurface = 0xFFF0DFD6.toInt(),
                    surfaceVariant = 0xFF52443B.toInt(),
                    onSurfaceVariant = 0xFFD7C3B7.toInt(),
                    scrim = 0xFF000000.toInt(),
                    inverseSurface = 0xFFF0DFD6.toInt(),
                    inverseOnSurface = 0xFF382F29.toInt(),
                    inversePrimary = 0xFF6D3B07.toInt(),
                    border = 0xFF6D3B07.toInt(),
                    borderVariant = 0xFF52443B.toInt(),
                )
            }

            AppThemeColors.UNRECOGNIZED -> {
                of(AppThemeColors.PURPLE)
            }
        }
}
