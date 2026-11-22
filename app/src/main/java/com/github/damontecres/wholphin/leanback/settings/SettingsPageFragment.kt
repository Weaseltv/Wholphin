package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.dp
import kotlinx.coroutines.CoroutineScope

/**
 * Base of every settings destination (no-Compose UI, 2026-11-22): the page background, an optional side pane
 * (release notes, the subtitle preview, the Home preview) and the settings panel, which holds a stack of Leanback
 * Preference levels ([SettingsListFragment]) and Leanback's preference dialogs (choices, text) in its own child
 * fragment back stack. BACK closes the top level or dialog first, then leaves the page (the shell goes back).
 *
 * Subclasses build each level's rows in [buildScreen] and call [refreshScreens] when their state changes.
 */
abstract class SettingsPageFragment :
    DestinationFragment(),
    PreferenceFragmentCompat.OnPreferenceDisplayDialogCallback {
    /** The section accent of this page (Neon focus border, values, rule) */
    val accent: Int get() = pageStyle.sectionAccent

    protected lateinit var root: ViewGroup
        private set

    /** The area beside the panel */
    protected lateinit var sidePane: FrameLayout
        private set

    /** The panel container */
    protected lateinit var panel: SettingsPanelFrame
        private set

    /** Panel width as a fraction of the page (the old `fillMaxWidth(.4f)`), unless [panelWidthDp] is set */
    protected open val panelFraction: Float = .4f

    /** A fixed panel width instead of [panelFraction] (Home settings: 360dp) */
    protected open val panelWidthDp: Int? = null

    /** The panel sits at the end of the page (settings) or at the start (Home settings) */
    protected open val panelAtEnd: Boolean = true

    /** The Neon 5dp accent stripe along the panel's inner edge (the main settings panels only) */
    protected open val panelStripe: Boolean = true

    /** Plain `titleLarge` level headings (Home settings) instead of the panel heading */
    open val plainHeadings: Boolean = false

    /** UP on the first row goes to the last and DOWN on the last to the first (the main and subtitle lists did) */
    open val wrapAroundRows: Boolean = false

    /** Horizontal padding of the rows inside the panel (the old list content padding) */
    open val listHorizontalPaddingDp: Int get() = if (SettingsLook.neon) 32 else 16

    /** The key of the first level */
    abstract val rootScreenKey: String

    /** Adds the rows of the level [key] to [screen]; register refreshes with [SettingsListFragment.onRefresh] */
    abstract fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    )

    /** The panel title of the level [key] */
    abstract fun screenTitle(key: String): CharSequence

    /** The section accent changed while the page is up: repaint the panel and the rows */
    override fun onPageStyleChanged(style: com.github.damontecres.wholphin.leanback.theme.PageStyle) {
        panel.accent = style.sectionAccent
        childFragmentManager.fragments.filterIsInstance<SettingsListFragment>().forEach { it.applyAccent(style.sectionAccent) }
    }

    /** Called when a level's list exists (eg to observe its selection) */
    open fun onListViewCreated(
        fragment: SettingsListFragment,
        grid: VerticalGridView,
    ) {}

    /** The app preferences the shell has now, so the rows show at once (the store's flow follows) */
    protected val initialAppPreferences: com.github.damontecres.wholphin.preferences.AppPreferences?
        get() = (activity as? com.github.damontecres.wholphin.leanback.shell.ScreenHost)?.userPreferences?.value?.appPreferences

    /** The scope for writes that should finish even if a level closes */
    val pageScope: CoroutineScope get() = lifecycleScope

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        sidePane =
            FrameLayout(ctx).apply {
                id = R.id.lb_settings_side
                clipChildren = false
            }
        panel =
            SettingsPanelFrame(ctx, accent, stripeAtStart = panelAtEnd, stripe = panelStripe).apply {
                id = R.id.lb_settings_panel
                setBackgroundColor(panelBackground())
                clipChildren = false
            }
        root =
            SettingsPageLayout(ctx, panel, sidePane, panelAtEnd) { width ->
                panelWidthDp?.let { ctx.dp(it) } ?: (width * panelFraction).toInt()
            }.apply {
                setBackgroundColor(pageBackground())
            }
        return root
    }

    /** The colour behind everything (the old page `background`) */
    protected open fun pageBackground(): Int = SettingsLook.pageBackground

    /** The panel colour: Neon `stage` or the 1dp surface (the main settings panels) */
    protected open fun panelBackground(): Int = SettingsLook.panelBackground

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.lb_settings_panel) == null) {
            childFragmentManager
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.lb_settings_panel, SettingsListFragment.newInstance(rootScreenKey))
                .commitNow()
            if (savedInstanceState == null) animatePanelIn()
        }
    }

    /** The old panels faded in and slid in from half their width */
    private fun animatePanelIn() {
        panel.alpha = 0f
        androidx.core.view.OneShotPreDrawListener.add(panel) {
            val distance = panel.width / 2f
            panel.translationX = if (panelAtEnd) distance else -distance
            panel
                .animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(PANEL_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Opens the level [key] in the panel (BACK returns to the current one) */
    fun openScreen(key: String) = openPanel(SettingsListFragment.newInstance(key))

    /** Shows [fragment] in the panel above the current level */
    fun openPanel(fragment: Fragment) {
        if (childFragmentManager.isStateSaved) return
        childFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                android.R.animator.fade_in,
                android.R.animator.fade_out,
                android.R.animator.fade_in,
                android.R.animator.fade_out,
            ).replace(R.id.lb_settings_panel, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Closes the top level or dialog; false when the first level is showing */
    fun closePanel(): Boolean {
        if (childFragmentManager.backStackEntryCount == 0 || childFragmentManager.isStateSaved) return false
        childFragmentManager.popBackStack()
        return true
    }

    override fun onBackPressed(): Boolean {
        val top = childFragmentManager.findFragmentById(R.id.lb_settings_panel)
        if (top is SettingsBackHandler && top.handleBack()) return true
        return closePanel()
    }

    /** The level currently in the panel, if the top is a level */
    val topList: SettingsListFragment?
        get() = childFragmentManager.findFragmentById(R.id.lb_settings_panel) as? SettingsListFragment

    /** Re-reads the rows of every level that is on screen; the others refresh when they show again */
    fun refreshScreens() {
        if (!isAdded) return
        childFragmentManager.fragments.filterIsInstance<SettingsListFragment>().forEach { it.refresh() }
    }

    /** First show: the panel's selected row. Back from a page above: the row that had focus (base behaviour) */
    override fun onScreenFocus(restoring: Boolean) {
        if (restoring) {
            super.onScreenFocus(true)
            return
        }
        if (!panel.requestFocus()) super.onScreenFocus(false)
    }

    /** Choices and text open in the panel as Leanback's preference dialogs (styled for WeaselPlex) */
    override fun onPreferenceDisplayDialog(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean {
        val fragment: Fragment =
            when (pref) {
                is ListPreference, is MultiSelectListPreference -> SettingsListDialogFragment.newInstance(pref.key)
                is EditTextPreference -> SettingsEditTextFragment.newInstance(pref.key)
                else -> return false
            }
        @Suppress("DEPRECATION")
        fragment.setTargetFragment(caller, 0)
        openPanel(fragment)
        return true
    }

    /** A short message, as the old screens showed with `Toast` */
    fun toast(
        message: CharSequence,
        long: Boolean = false,
    ) {
        Toast.makeText(requireContext(), message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PANEL_IN_MS = 300L
    }
}

/**
 * The settings panel's frame: the panel background and, on the WeaselTV theme, the 5dp accent stripe along the
 * panel's inner edge, drawn over the content as the old panel did.
 */
class SettingsPanelFrame(
    context: Context,
    accent: Int,
    private val stripeAtStart: Boolean,
    private val stripe: Boolean,
) : FrameLayout(context) {
    private val stripePaint = Paint().apply { color = accent }

    /** The page accent (the Neon stripe) */
    var accent: Int = accent
        set(value) {
            field = value
            stripePaint.color = value
            invalidate()
        }

    private val stripeWidth = context.dp(5).toFloat()

    init {
        setWillNotDraw(false)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (stripe && SettingsLook.neon) {
            if (stripeAtStart) {
                canvas.drawRect(0f, 0f, stripeWidth, height.toFloat(), stripePaint)
            } else {
                canvas.drawRect(width - stripeWidth, 0f, width.toFloat(), height.toFloat(), stripePaint)
            }
        }
    }
}

/**
 * A settings page's layout: the panel at a fixed width or a fraction of the page, at the start or the end, and the
 * side pane in the rest of the width.
 */
internal class SettingsPageLayout(
    context: Context,
    private val panel: View,
    private val side: View,
    private val panelAtEnd: Boolean,
    private val panelWidth: (pageWidth: Int) -> Int,
) : ViewGroup(context) {
    init {
        clipChildren = false
        addView(side)
        addView(panel)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val panelPx = panelWidth(width).coerceIn(0, width)
        panel.measure(
            MeasureSpec.makeMeasureSpec(panelPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        side.measure(
            MeasureSpec.makeMeasureSpec(width - panelPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val width = r - l
        val height = b - t
        val panelPx = panel.measuredWidth
        if (panelAtEnd) {
            side.layout(0, 0, width - panelPx, height)
            panel.layout(width - panelPx, 0, width, height)
        } else {
            panel.layout(0, 0, panelPx, height)
            side.layout(panelPx, 0, width, height)
        }
    }
}
