package com.github.damontecres.wholphin.leanback.playback

import android.content.res.Configuration
import android.graphics.Typeface
import androidx.annotation.Dimension
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.github.damontecres.wholphin.mpv.MPVLib
import com.github.damontecres.wholphin.preferences.BackgroundStyle
import com.github.damontecres.wholphin.preferences.EdgeStyle
import com.github.damontecres.wholphin.preferences.SubtitlePreferences
import com.github.damontecres.wholphin.util.mpv.setPropertyColor
import timber.log.Timber

/**
 * The subtitle style settings applied to the player's subtitle renderers (no-Compose UI, 2026-11-22).
 *
 * A Compose-free private copy of `SubtitleSettings.toSubtitleStyle` / `applyTo` / `calculateEdgeSize` /
 * `applyToMpv` (`ui/preferences/subtitle/SubtitleSettings.kt`, owned by the settings area), with the same maths:
 * `Density` became the display density and font scale.
 */
object PlaybackSubtitleStyle {
    private fun combine(
        color: Int,
        opacity: Int,
    ) = ((opacity / 100.0 * 255).toInt().shl(24)).or(color.and(0x00FFFFFF))

    @OptIn(UnstableApi::class)
    fun toCaptionStyle(prefs: SubtitlePreferences): CaptionStyleCompat {
        val bg = combine(prefs.backgroundColor, prefs.backgroundOpacity)
        return CaptionStyleCompat(
            combine(prefs.fontColor, prefs.fontOpacity),
            if (prefs.backgroundStyle == BackgroundStyle.BG_WRAP) bg else 0,
            if (prefs.backgroundStyle == BackgroundStyle.BG_BOXED) bg else 0,
            when (prefs.edgeStyle) {
                EdgeStyle.EDGE_NONE, EdgeStyle.UNRECOGNIZED -> CaptionStyleCompat.EDGE_TYPE_NONE
                EdgeStyle.EDGE_SOLID -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                EdgeStyle.EDGE_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            },
            combine(prefs.edgeColor, prefs.fontOpacity),
            when {
                prefs.fontBold && prefs.fontItalic -> Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
                prefs.fontBold -> Typeface.defaultFromStyle(Typeface.BOLD)
                prefs.fontItalic -> Typeface.defaultFromStyle(Typeface.ITALIC)
                else -> Typeface.DEFAULT
            },
        )
    }

    /** Style, fixed text size and bottom margin of a Media3 [SubtitleView] */
    @OptIn(UnstableApi::class)
    fun applyTo(
        prefs: SubtitlePreferences,
        view: SubtitleView,
    ) {
        view.setStyle(toCaptionStyle(prefs))
        view.setFixedTextSize(Dimension.SP, prefs.fontSize.toFloat())
        view.setBottomPaddingFraction(prefs.margin.toFloat() / 100f)
    }

    /** The outline thickness in px: half the edge thickness setting, in dp */
    fun edgeSizePx(
        prefs: SubtitlePreferences,
        density: Float,
    ): Float = (prefs.edgeThickness / 2f) * density

    /** Push the subtitle settings to libmpv, sized for the display [density] and [fontScale] */
    fun applyToMpv(
        prefs: SubtitlePreferences,
        configuration: Configuration,
        density: Float,
        fontScale: Float = 1f,
    ) {
        val fc = combine(prefs.fontColor, prefs.fontOpacity)
        val bg = combine(prefs.backgroundColor, prefs.backgroundOpacity)
        val edge = combine(prefs.edgeColor, prefs.fontOpacity)

        // TODO weird, but seems to get the size to be very close to matching sizes between renderers
        val fontSizePx = (prefs.fontSize * fontScale * density * .8).toInt()
        MPVLib.setPropertyInt("sub-font-size", fontSizePx)
        MPVLib.setPropertyColor("sub-color", fc)
        MPVLib.setPropertyColor("sub-outline-color", edge)

        val heightInPx = configuration.screenHeightDp * density
        val margin = (heightInPx * (prefs.margin.toFloat() / 100f) * .8).toInt()
        MPVLib.setPropertyInt("sub-margin-y", margin)
        Timber.d("MPV subtitles: fontSizePx=%s, margin=$margin", fontSizePx, margin)

        when (prefs.edgeStyle) {
            EdgeStyle.EDGE_NONE,
            EdgeStyle.UNRECOGNIZED,
            -> {
                MPVLib.setPropertyInt("sub-shadow-offset", 0)
                MPVLib.setPropertyDouble("sub-outline-size", 0.0)
            }

            EdgeStyle.EDGE_SOLID -> {
                MPVLib.setPropertyInt("sub-shadow-offset", 0)
                MPVLib.setPropertyDouble("sub-outline-size", 1.15)
            }

            EdgeStyle.EDGE_SHADOW -> {
                MPVLib.setPropertyInt("sub-shadow-offset", 4)
                MPVLib.setPropertyDouble("sub-outline-size", 0.0)
            }
        }
        val outlineSizePx = edgeSizePx(prefs, density) * .8
        MPVLib.setPropertyDouble("sub-outline-size", outlineSizePx)

        MPVLib.setPropertyBoolean("sub-bold", prefs.fontBold)
        MPVLib.setPropertyBoolean("sub-italic", prefs.fontItalic)

        MPVLib.setPropertyColor("sub-back-color", bg)
        val borderStyle =
            when (prefs.backgroundStyle) {
                BackgroundStyle.UNRECOGNIZED,
                BackgroundStyle.BG_NONE,
                -> "outline-and-shadow"

                BackgroundStyle.BG_WRAP -> "opaque-box"

                BackgroundStyle.BG_BOXED -> "background-box"
            }
        MPVLib.setPropertyString("sub-border-style", borderStyle)
    }
}
