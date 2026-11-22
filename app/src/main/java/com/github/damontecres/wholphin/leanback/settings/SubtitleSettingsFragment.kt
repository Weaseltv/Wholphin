package com.github.damontecres.wholphin.leanback.settings

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.fragment.app.viewModels
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.preference.PreferenceScreen
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.SubtitlePreferences
import com.github.damontecres.wholphin.preferences.resetSubtitles
import com.github.damontecres.wholphin.preferences.updateInterfacePreferences
import com.github.damontecres.wholphin.ui.findActivity
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.PreferencesViewModel
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.toSubtitleStyle
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.Media3SubtitleOverride
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Subtitle style (`Destination.SubtitleSettings`, SDR or HDR) on Leanback Preference (no-Compose UI, 2026-11-22): the
 * old `SubtitleStylePage`. The style rows sit in a panel on the right quarter; the rest of the page previews the
 * subtitles over a still image with the current style (the margin, or the image-subtitle dimming, while those rows
 * have focus). The HDR page switches the window to HDR colour mode while it shows. On an HDR display the SDR page also
 * offers "Different for HDR" and the HDR style page.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class SubtitleSettingsFragment : SettingsPageFragment() {
    private val viewModel: PreferencesViewModel by viewModels()

    private val hdr: Boolean get() = requireDestination<Destination.SubtitleSettings>().hdr

    private var appPreferences: AppPreferences? = null
    private var focusedMargin = false
    private var focusedImageOpacity = false
    private var preview: FrameLayout? = null

    override val rootScreenKey: String = "subtitles"
    override val panelFraction: Float = .25f
    override val panelStripe: Boolean = false
    override val listHorizontalPaddingDp: Int = 16
    override val wrapAroundRows: Boolean = true

    override fun panelBackground(): Int = SettingsLook.tonalSurface(1f, accent)

    override fun screenTitle(key: String): CharSequence = getString(if (hdr) R.string.hdr_subtitle_style else R.string.subtitle_style)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = initialAppPreferences
    }

    private val subtitlePreferences: SubtitlePreferences?
        get() =
            appPreferences?.interfacePreferences?.let {
                if (hdr) it.hdrSubtitlesPreferences else it.subtitlesPreferences
            }

    override fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    ) {
        val display = requireActivity().window.decorView.display
        val groups =
            if (!hdr && display != null && SubtitleSettings.shouldShowHdr(display)) {
                // If not on the HDR page and the display is HDR capable, offer the HDR style
                SubtitleSettings.preferences + SubtitleSettings.hdrPreferenceGroup
            } else {
                SubtitleSettings.preferences
            }
        val binder =
            AppPreferenceBinder<SubtitlePreferences>(
                fragment = fragment,
                accent = accent,
                current = { subtitlePreferences },
                update = ::save,
                navigate = viewModel.navigationManager::navigateTo,
                onClickable = { pref ->
                    if (pref == SubtitleSettings.Reset) {
                        save { SubtitlePreferences.newBuilder().apply { resetSubtitles() }.build() }
                    }
                },
                largeCategories = false,
            )
        binder.afterAdd = { pref, row, _ ->
            when (pref) {
                SubtitleSettings.Margin -> {
                    (row as? SettingsRow)?.onFocusChange = {
                        focusedMargin = it
                        renderPreview()
                    }
                }

                SubtitleSettings.ImageOpacity -> {
                    (row as? SettingsRow)?.onFocusChange = {
                        focusedImageOpacity = it
                        renderPreview()
                    }
                }

                else -> {}
            }
        }
        binder.addGroups(screen, groups)
    }

    /** Saves the style this page edits (SDR or HDR) */
    private fun save(transform: (SubtitlePreferences) -> SubtitlePreferences) {
        pageScope.launch(ExceptionHandler()) {
            viewModel.preferenceDataStore.updateData {
                it.updateInterfacePreferences {
                    if (hdr) {
                        hdrSubtitlesPreferences = transform(hdrSubtitlesPreferences)
                    } else {
                        subtitlesPreferences = transform(subtitlesPreferences)
                    }
                }
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        sidePane.addView(
            ImageView(ctx).apply {
                setImageResource(R.mipmap.eclipse)
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        preview =
            FrameLayout(ctx).also {
                sidePane.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hdr) {
            Timber.v("Switching color mode to HDR")
            ctx.findActivity()?.window?.colorMode = ActivityInfo.COLOR_MODE_HDR
        }
        viewModel.preferenceDataStore.data.collectWhileStarted {
            appPreferences = it
            refreshScreens()
            renderPreview()
        }
    }

    /** The preview: three sample lines, or the margin / image dimming while those rows have focus */
    private fun renderPreview() {
        val frame = preview ?: return
        val prefs = subtitlePreferences ?: return
        val ctx = frame.context
        val density = resources.displayMetrics.density
        frame.removeAllViews()

        fun subtitleView(
            text: String,
            style: SubtitlePreferences,
        ) = SubtitleView(ctx).apply {
            setStyle(style.toSubtitleStyle())
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.fontSize.toFloat())
            setCues(listOf(Cue.Builder().setText(text).build()))
        }
        when {
            focusedMargin -> {
                frame.addView(
                    subtitleView("Subtitles margin below here", prefs).apply {
                        setBottomPaddingFraction(prefs.margin.toFloat() / 100f)
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            }

            focusedImageOpacity -> {
                val defaultStyle = SubtitlePreferences.newBuilder().apply { resetSubtitles() }.build()
                frame.addView(
                    SubtitleView(ctx).apply {
                        setStyle(defaultStyle.toSubtitleStyle())
                        setCues(listOf(Cue.Builder().setText("ExoPlayer only:\nImage based subtitles can be dimmed.").build()))
                        alpha = prefs.imageSubtitleOpacity / 100f
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            }

            else -> {
                val column =
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        val pad = ctx.dp(16)
                        setPadding(pad, pad, pad, pad)
                    }
                listOf(
                    "Subtitles will look like this" to 48,
                    "This is another example" to 24,
                    "Longer multi line subtitles will\nlook like this" to 0,
                ).forEach { (text, bottom) ->
                    val view =
                        subtitleView(text, prefs).also {
                            Media3SubtitleOverride(prefs.calculateEdgeSize(density)).apply(it)
                        }
                    column.addView(
                        view,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                            bottomMargin = ctx.dp(bottom)
                        },
                    )
                }
                frame.addView(
                    column,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            }
        }
    }

    override fun onDestroyView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context?.findActivity()?.window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
        preview = null
        super.onDestroyView()
    }
}
