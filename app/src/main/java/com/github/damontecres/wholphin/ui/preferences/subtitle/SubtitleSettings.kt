package com.github.damontecres.wholphin.ui.preferences.subtitle

import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.view.Display
import androidx.annotation.Dimension
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.mpv.MPVLib
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppDestinationPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.BackgroundStyle
import com.github.damontecres.wholphin.preferences.EdgeStyle
import com.github.damontecres.wholphin.preferences.InterfacePreferences
import com.github.damontecres.wholphin.preferences.SubtitlePreferences
import com.github.damontecres.wholphin.preferences.resetSubtitles
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.ConditionalPreferences
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup
import timber.log.Timber

/**
 * The subtitle style preferences and how they apply to ExoPlayer and MPV. Compose-free since the no-Compose rewrite
 * (2026-11-22): colours are plain ARGB ints, densities are plain floats (`displayMetrics.density`).
 */
object SubtitleSettings {
    /** Compose `Color.White` etc. as ARGB ints, in the order of `R.array.font_colors` */
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val TRANSPARENT = 0x00000000
    val FontSize =
        AppSliderPreference<SubtitlePreferences>(
            title = R.string.font_size,
            defaultValue = 24,
            min = 8,
            max = 70,
            interval = 2,
            getter = {
                it.fontSize.toLong()
            },
            setter = { prefs, value ->
                prefs.update { fontSize = value.toInt() }
            },
            summarizer = { value -> value?.toString() },
        )

    private val colorList =
        listOf(
            WHITE,
            BLACK,
            0xFFCCCCCC.toInt(),
            0xFF444444.toInt(),
            0xFFFF0000.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(),
            0xFFFF00FF.toInt(),
        )

    val FontColor =
        AppChoicePreference<SubtitlePreferences, Int>(
            title = R.string.font_color,
            defaultValue = WHITE,
            getter = { it.fontColor },
            setter = { prefs, value ->
                prefs.update { fontColor = value.and(0x00FFFFFF) }
            },
            displayValues = R.array.font_colors,
            indexToValue = { colorList.getOrNull(it) ?: WHITE },
            valueToIndex = { value ->
                val color = value.and(0x00FFFFFF)
                colorList.indexOfFirstOrNull { color == it.and(0x00FFFFFF) } ?: 0
            },
        )

    val FontBold =
        AppSwitchPreference<SubtitlePreferences>(
            title = R.string.bold_font,
            defaultValue = false,
            getter = { it.fontBold },
            setter = { prefs, value ->
                prefs.update { fontBold = value }
            },
        )
    val FontItalic =
        AppSwitchPreference<SubtitlePreferences>(
            title = R.string.italic_font,
            defaultValue = false,
            getter = { it.fontItalic },
            setter = { prefs, value ->
                prefs.update { fontItalic = value }
            },
        )

    val FontOpacity =
        AppSliderPreference<SubtitlePreferences>(
            title = R.string.font_opacity,
            defaultValue = 100,
            min = 10,
            max = 100,
            interval = 10,
            getter = {
                it.fontOpacity
                    .toLong()
            },
            setter = { prefs, value ->
                prefs.update { fontOpacity = value.toInt() }
            },
            summarizer = { value -> value?.let { "$it%" } },
        )

    val EdgeStylePref =
        AppChoicePreference<SubtitlePreferences, EdgeStyle>(
            title =
                R.string.edge_style,
            defaultValue = EdgeStyle.EDGE_SOLID,
            getter = { it.edgeStyle },
            setter = { prefs, value ->
                prefs.update { edgeStyle = value }
            },
            displayValues = R.array.subtitle_edge,
            indexToValue = { EdgeStyle.forNumber(it) },
            valueToIndex = { it.number },
        )

    val EdgeColor =
        AppChoicePreference<SubtitlePreferences, Int>(
            title = R.string.edge_color,
            defaultValue = BLACK,
            getter = { it.edgeColor },
            setter = { prefs, value ->
                prefs.update { edgeColor = value.and(0x00FFFFFF) }
            },
            displayValues = R.array.font_colors,
            indexToValue = { colorList.getOrNull(it) ?: WHITE },
            valueToIndex = { value ->
                val color = value.and(0x00FFFFFF)
                colorList.indexOfFirstOrNull { color == it.and(0x00FFFFFF) } ?: 0
            },
        )

    val EdgeThickness =
        AppSliderPreference<SubtitlePreferences>(
            title = R.string.edge_size,
            defaultValue = 4,
            min = 1,
            max = 32,
            interval = 1,
            getter = {
                it.edgeThickness
                    .toLong()
            },
            setter = { prefs, value ->
                prefs.update { edgeThickness = value.toInt() }
            },
            summarizer = { value -> value?.let { "${it / 2.0}" } },
        )

    val BackgroundColor =
        AppChoicePreference<SubtitlePreferences, Int>(
            title = R.string.background_color,
            defaultValue = TRANSPARENT,
            getter = { it.backgroundColor },
            setter = { prefs, value ->
                prefs.update { backgroundColor = value.and(0x00FFFFFF) }
            },
            displayValues = R.array.font_colors,
            indexToValue = { colorList.getOrNull(it) ?: WHITE },
            valueToIndex = { value ->
                val color = value.and(0x00FFFFFF)
                colorList.indexOfFirstOrNull { color == it.and(0x00FFFFFF) } ?: 0
            },
        )

    val BackgroundOpacity =
        AppSliderPreference<SubtitlePreferences>(
            title = R.string.background_opacity,
            defaultValue = 50,
            min = 10,
            max = 100,
            interval = 10,
            getter = {
                it.backgroundOpacity
                    .toLong()
            },
            setter = { prefs, value ->
                prefs.update { backgroundOpacity = value.toInt() }
            },
            summarizer = { value -> value?.let { "$it%" } },
        )

    val BackgroundStylePref =
        AppChoicePreference<SubtitlePreferences, BackgroundStyle>(
            title =
                R.string.background_style,
            defaultValue = BackgroundStyle.BG_NONE,
            getter = { it.backgroundStyle },
            setter = { prefs, value ->
                prefs.update { backgroundStyle = value }
            },
            displayValues = R.array.background_style,
            indexToValue = { BackgroundStyle.forNumber(it) },
            valueToIndex = { it.number },
        )

    val Margin =
        AppSliderPreference<SubtitlePreferences>(
            title = R.string.subtitle_margin,
            defaultValue = 8,
            min = 0,
            max = 100,
            interval = 1,
            getter = {
                it.margin
                    .toLong()
            },
            setter = { prefs, value ->
                prefs.update { margin = value.toInt() }
            },
            summarizer = { value -> value?.let { "$it%" } },
        )

    val ImageOpacity =
        AppSliderPreference<SubtitlePreferences>(
            title = R.string.image_subtitle_opacity,
            defaultValue = 100,
            min = 10,
            max = 100,
            interval = 5,
            getter = {
                it.imageSubtitleOpacity.toLong()
            },
            setter = { prefs, value ->
                prefs.update { imageSubtitleOpacity = value.toInt() }
            },
            summarizer = { value -> value?.let { "$it%" } },
        )

    val Reset =
        AppClickablePreference<SubtitlePreferences>(
            title = R.string.reset,
            getter = { },
            setter = { prefs, _ -> prefs },
        )

    val SeparateHdr =
        AppSwitchPreference<SubtitlePreferences>(
            title = R.string.different_for_hdr,
            defaultValue = false,
            getter = { it.useSeparateHdr },
            setter = { prefs, value ->
                prefs.update { useSeparateHdr = value }
            },
        )

    val HdrSettings =
        AppDestinationPreference<SubtitlePreferences>(
            title = R.string.hdr_subtitle_style,
            destination = Destination.SubtitleSettings(true),
        )

    val preferences =
        listOf(
            PreferenceGroup(
                title = R.string.font,
                preferences =
                    listOf(
                        FontSize,
                        FontColor,
                        FontBold,
                        FontItalic,
                        FontOpacity,
                    ),
            ),
            PreferenceGroup(
                title = R.string.edge_style,
                preferences =
                    listOf(
                        EdgeStylePref,
                        EdgeColor,
                        EdgeThickness,
                    ),
            ),
            PreferenceGroup(
                title = R.string.background,
                preferences =
                    listOf(
                        BackgroundStylePref,
                        BackgroundColor,
                        BackgroundOpacity,
                    ),
            ),
            PreferenceGroup(
                title = R.string.more,
                preferences =
                    listOf(
                        Margin,
                        ImageOpacity,
                        Reset,
                    ),
            ),
        )

    val hdrPreferenceGroup =
        listOf(
            PreferenceGroup(
                title = R.string.hdr,
                preferences =
                    listOf(
                        SeparateHdr,
                    ),
                conditionalPreferences =
                    listOf(
                        ConditionalPreferences(
                            condition = { it.useSeparateHdr },
                            preferences = listOf(HdrSettings),
                        ),
                    ),
            ),
        )

    fun shouldShowHdr(display: Display): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && display.isHdr

    private fun combine(
        color: Int,
        opacity: Int,
    ) = ((opacity / 100.0 * 255).toInt().shl(24)).or(color.and(0x00FFFFFF))

    @OptIn(UnstableApi::class)
    fun SubtitlePreferences.toSubtitleStyle(): CaptionStyleCompat {
        val bg = combine(backgroundColor, backgroundOpacity)
        return CaptionStyleCompat(
            combine(fontColor, fontOpacity),
            if (backgroundStyle == BackgroundStyle.BG_WRAP) bg else 0,
            if (backgroundStyle == BackgroundStyle.BG_BOXED) bg else 0,
            when (edgeStyle) {
                EdgeStyle.EDGE_NONE, EdgeStyle.UNRECOGNIZED -> CaptionStyleCompat.EDGE_TYPE_NONE
                EdgeStyle.EDGE_SOLID -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                EdgeStyle.EDGE_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            },
            combine(edgeColor, fontOpacity),
            when {
                fontBold && fontItalic -> Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
                fontBold -> Typeface.defaultFromStyle(Typeface.BOLD)
                fontItalic -> Typeface.defaultFromStyle(Typeface.ITALIC)
                else -> Typeface.DEFAULT
            },
        )
    }

    @OptIn(UnstableApi::class)
    fun SubtitlePreferences.applyTo(view: SubtitleView) {
        view.setStyle(toSubtitleStyle())
        view.setFixedTextSize(Dimension.SP, fontSize.toFloat())
        view.setBottomPaddingFraction(margin.toFloat() / 100f)
    }

    /**
     * The edge (outline) size in px for a display with [density] px per dp (`displayMetrics.density`).
     */
    fun SubtitlePreferences.calculateEdgeSize(density: Float): Float = (edgeThickness / 2f) * density

    /**
     * Applies this style to MPV. [density] is px per dp and [fontScale] the system font scale
     * (`displayMetrics.density`, `configuration.fontScale`), as the Compose `Density` gave them.
     */
    fun SubtitlePreferences.applyToMpv(
        configuration: Configuration,
        density: Float,
        fontScale: Float = configuration.fontScale,
    ) {
        val fc = combine(fontColor, fontOpacity)
        val bg = combine(backgroundColor, backgroundOpacity)
        val edge = combine(edgeColor, fontOpacity)

        // TODO weird, but seems to get the size to be very close to matching sizes between renderers
        val fontSizePx = (fontSize * fontScale * density * .8).toInt()
        MPVLib.setPropertyInt("sub-font-size", fontSizePx)
        setMpvColor("sub-color", fc)
        setMpvColor("sub-outline-color", edge)

        val heightInPx = configuration.screenHeightDp * density
        val margin = (heightInPx * (margin.toFloat() / 100f) * .8).toInt()
        MPVLib.setPropertyInt("sub-margin-y", margin)
        Timber.d("MPV subtitles: fontSizePx=%s, margin=$margin", fontSizePx, margin)

        when (edgeStyle) {
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
        val outlineSizePx = calculateEdgeSize(density) * .8
        MPVLib.setPropertyDouble("sub-outline-size", outlineSizePx)

//        if (fontBold) {
//            MPVLib.setPropertyString("sub-font", "Roboto Bold")
//        } else {
//            MPVLib.setPropertyString("sub-font", "Roboto Regular")
//        }
        MPVLib.setPropertyBoolean("sub-bold", fontBold)
        MPVLib.setPropertyBoolean("sub-italic", fontItalic)

        setMpvColor("sub-back-color", bg)
        val borderStyle =
            when (backgroundStyle) {
                BackgroundStyle.UNRECOGNIZED,
                BackgroundStyle.BG_NONE,
                -> "outline-and-shadow"

                BackgroundStyle.BG_WRAP -> "opaque-box"

                BackgroundStyle.BG_BOXED -> "background-box"
            }
        MPVLib.setPropertyString("sub-border-style", borderStyle)
    }
}

/** MPV's "r/g/b/a" colour format with 0..1 channels, as `util/mpv/Utils.kt` formats a Compose `Color` */
private fun setMpvColor(
    property: String,
    argb: Int,
) {
    fun channel(shift: Int): Float = ((argb shr shift) and 0xFF) / 255f
    MPVLib.setPropertyString(property, "${channel(16)}/${channel(8)}/${channel(0)}/${channel(24)}")
}

inline fun SubtitlePreferences.update(block: SubtitlePreferences.Builder.() -> Unit): SubtitlePreferences = toBuilder().apply(block).build()

fun InterfacePreferences.shouldEnableSeparateHdrToggle(): Boolean {
    // If already separate, leave it
    if (subtitlesPreferences.useSeparateHdr) return true
    val defaultStyle =
        SubtitlePreferences
            .newBuilder()
            .apply {
                resetSubtitles()
            }.build()
    // If SDR and HDR are the same, don't separate
    return if (subtitlesPreferences == hdrSubtitlesPreferences) {
        false
    } else {
        // If different, but HDR has not been changed from original settings, don't separate
        hdrSubtitlesPreferences != defaultStyle
    }
}
