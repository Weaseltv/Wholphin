package com.github.damontecres.wholphin.leanback.settings

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.leanback.widget.VerticalGridView
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.RailAccents
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppDestinationPreference
import com.github.damontecres.wholphin.preferences.AppMultiChoicePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppStringPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference

/**
 * One row of a [PreferencePanel]: a declarative [preference] definition, shown while [visible] holds for the panel's
 * value and enabled while [enabled] holds. For an [AppClickablePreference], [onClick] gives the new value (Reset: the
 * defaults).
 */
internal class PanelPreference<P : Any>(
    val preference: AppPreference<P, *>,
    val visible: (P) -> Boolean = { true },
    val enabled: (P) -> Boolean = { true },
    val onClick: ((P) -> P)? = null,
)

/**
 * A side panel of AndroidX Leanback Preference rows over the page (no-Compose UI, 2026-11-22): the old
 * `PreferenceOptionsDialog` showing `ComposablePreference` rows, which the view options of the Watchlist / folder
 * pages, the library pages and the collection page use.
 *
 * The panel is [WIDTH_DP] wide at the end of the screen, as tall as its rows up to [MAX_HEIGHT_DP], over the page
 * without dimming it, on the dialog panel (Neon card, `line2` edge and the accent tally). It holds the settings
 * framework's Leanback Preference list ([SettingsListFragment] with the [AppPreferenceBinder] rows, [SettingsLook]):
 * switches toggle on OK, sliders move with LEFT / RIGHT, choices open Leanback's list panel in its place (BACK returns
 * to the rows), clickable rows run their [PanelPreference.onClick].
 *
 * The rows edit the in-memory [value]; every change is passed to [onChange] at once, and [update] shows a value the
 * page saved. BACK on the rows closes the panel ([onDismissRequest]); the platform gives focus back to the view that
 * opened it.
 */
internal class PreferencePanel<P : Any>(
    @param:StringRes val title: Int,
    initial: P,
    private val preferences: List<PanelPreference<P>>,
    /** The page's section accent (focus border, values, heading rule, tally) */
    val accent: Int,
    private val onChange: (P) -> Unit,
) {
    /** The user closed the panel with BACK */
    var onDismissRequest: (() -> Unit)? = null

    /** The value the rows show and edit */
    var value: P = initial
        private set

    private var fragment: PreferencePanelDialogFragment? = null

    /** The panel is open (or opening) */
    val isShowing: Boolean get() = fragment != null

    /** Opens the panel as a child of the page's [fragmentManager] (its child fragment manager) */
    fun show(fragmentManager: FragmentManager) {
        if (fragment != null || fragmentManager.isStateSaved) return
        val dialog = PreferencePanelDialogFragment()
        dialog.panel = this
        fragment = dialog
        dialog.show(fragmentManager, TAG)
    }

    /** The page saved [newValue] (eg after a change from this panel): show it */
    fun update(newValue: P) {
        if (newValue == value) return
        value = newValue
        fragment?.refreshRows()
    }

    /** Closes the panel without [onDismissRequest] */
    fun dismiss() {
        val dialog = fragment ?: return
        fragment = null
        dialog.dismissAllowingStateLoss()
    }

    internal fun onClosed(dialog: PreferencePanelDialogFragment) {
        if (fragment === dialog) fragment = null
    }

    /** Adds a row per definition to [screen] (the level of [list]) */
    internal fun bind(
        list: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        val binder =
            AppPreferenceBinder<P>(
                fragment = list,
                accent = accent,
                current = { value },
                update = { transform -> change(transform(value)) },
                navigate = {},
                onClickable = { pref ->
                    preferences
                        .firstOrNull { it.preference === pref }
                        ?.onClick
                        ?.invoke(value)
                        ?.let(::change)
                },
            )
        preferences.forEachIndexed { index, entry ->
            val row = binder.add(screen, entry.preference, "p$index", entry.visible)
            list.onRefresh { row.isEnabled = entry.enabled(value) }
        }
    }

    private fun change(newValue: P) {
        value = newValue
        onChange(newValue)
        fragment?.refreshRows()
    }

    companion object {
        const val WIDTH_DP = 320
        const val MAX_HEIGHT_DP = 480
        private const val TAG = "lb_settings.preference_panel"
    }
}

/**
 * The window of a [PreferencePanel]: an [AppDialog] at the end of the screen without dimming, whose panel holds the
 * panel's levels as child fragments, like a settings page's panel ([SettingsPageFragment]): the rows
 * ([SettingsListFragment]) and Leanback's preference dialogs for choices and text, with BACK closing the top one first.
 *
 * Recreated by the system (its definitions and callbacks are gone), it closes itself, as the old Compose panel did.
 */
class PreferencePanelDialogFragment :
    DialogFragment(),
    SettingsListHost,
    PreferenceFragmentCompat.OnPreferenceDisplayDialogCallback {
    internal var panel: PreferencePanel<*>? = null

    override val accent: Int get() = panel?.accent ?: RailAccents.SETTINGS
    override val plainHeadings: Boolean = false
    override val wrapAroundRows: Boolean = false

    /** The old panel list's 16dp content padding, for the rows and the choices */
    override val listHorizontalPaddingDp: Int = 16
    override val choiceListHorizontalPaddingDp: Int = 16

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (panel == null) {
            showsDialog = false
            dismissAllowingStateLoss()
            return
        }
        if (childFragmentManager.findFragmentById(R.id.lb_settings_options_panel) == null) {
            childFragmentManager
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.lb_settings_options_panel, SettingsListFragment.newInstance(ROOT_KEY))
                .commitNow()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val panelAccent = accent
        val dialog =
            object : AppDialog(
                context,
                DialogOptions(usePlatformDefaultWidth = false, gravity = Gravity.END),
                accent = panelAccent,
            ) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK && hasInnerLevel()) {
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeInnerLevel()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }
        dialog.setPanelContent(
            PreferencePanelFrame(context) {
                if (isAdded) childFragmentManager.findFragmentById(R.id.lb_settings_options_panel)?.view else null
            },
            FrameLayout.LayoutParams(context.dp(PreferencePanel.WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dialog.window?.setDimAmount(0f)
        // The levels' container must exist before their views are created (they are found in the dialog)
        dialog.create()
        return dialog
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        panel?.onDismissRequest?.invoke()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        panel?.onClosed(this)
    }

    override fun onDestroy() {
        panel?.onClosed(this)
        super.onDestroy()
    }

    override fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    ) {
        panel?.bind(fragment, screen)
    }

    override fun screenTitle(key: String): CharSequence = panel?.let { getString(it.title) }.orEmpty()

    /** Re-reads every row from the panel's value */
    internal fun refreshRows() {
        if (!isAdded) return
        childFragmentManager.fragments.filterIsInstance<SettingsListFragment>().forEach { it.refresh() }
    }

    /** Choices and text open in the panel, in place of the rows, as in the settings panels */
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
        if (childFragmentManager.isStateSaved) return true
        @Suppress("DEPRECATION")
        fragment.setTargetFragment(caller, 0)
        childFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                android.R.animator.fade_in,
                android.R.animator.fade_out,
                android.R.animator.fade_in,
                android.R.animator.fade_out,
            ).replace(R.id.lb_settings_options_panel, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    private fun hasInnerLevel(): Boolean = isAdded && childFragmentManager.backStackEntryCount > 0

    /** BACK on a choice list (or text) returns to the rows */
    private fun closeInnerLevel() {
        val top = childFragmentManager.findFragmentById(R.id.lb_settings_options_panel)
        if (top is SettingsBackHandler && top.handleBack()) return
        if (!childFragmentManager.isStateSaved) childFragmentManager.popBackStack()
    }

    private companion object {
        const val ROOT_KEY = "panel"
    }
}

/** A [definition][this] of a part of a bigger value: [get] reads the part from the value, [set] puts it back */
@Suppress("UNCHECKED_CAST")
internal fun <Q, P> AppPreference<Q, *>.forPart(
    get: (P) -> Q,
    set: (P, Q) -> P,
): AppPreference<P, *> {
    fun <T> AppPreference<Q, T>.partGetter(): (P) -> T = { getter(get(it)) }

    fun <T> AppPreference<Q, T>.partSetter(): (P, T) -> P = { p, v -> set(p, setter(get(p), v)) }
    return when (this) {
        is AppSwitchPreference<*> -> {
            val pref = this as AppSwitchPreference<Q>
            AppSwitchPreference<P>(
                title = pref.title,
                defaultValue = pref.defaultValue,
                getter = pref.partGetter(),
                setter = pref.partSetter(),
                validator = { p, v -> pref.validator(get(p), v) },
                summary = pref.summary,
                summaryOn = pref.summaryOn,
                summaryOff = pref.summaryOff,
            )
        }

        is AppChoicePreference<*, *> -> {
            val pref = this as AppChoicePreference<Q, Any?>
            AppChoicePreference<P, Any?>(
                title = pref.title,
                defaultValue = pref.defaultValue,
                displayValues = pref.displayValues,
                indexToValue = pref.indexToValue,
                valueToIndex = pref.valueToIndex,
                getter = pref.partGetter(),
                setter = pref.partSetter(),
                summary = pref.summary,
                subtitles = pref.subtitles,
            )
        }

        is AppMultiChoicePreference<*, *> -> {
            val pref = this as AppMultiChoicePreference<Q, Any?>
            AppMultiChoicePreference<P, Any?>(
                title = pref.title,
                defaultValue = pref.defaultValue,
                allValues = pref.allValues,
                displayValues = pref.displayValues,
                getter = pref.partGetter(),
                setter = pref.partSetter(),
                summary = pref.summary,
                displayValuesSubtitles = pref.displayValuesSubtitles,
            )
        }

        is AppSliderPreference<*> -> {
            val pref = this as AppSliderPreference<Q>
            AppSliderPreference<P>(
                title = pref.title,
                defaultValue = pref.defaultValue,
                min = pref.min,
                max = pref.max,
                interval = pref.interval,
                getter = pref.partGetter(),
                setter = pref.partSetter(),
                summary = pref.summary,
                summarizer = pref.summarizer,
            )
        }

        is AppStringPreference<*> -> {
            val pref = this as AppStringPreference<Q>
            object : AppStringPreference<P>(pref.title, pref.defaultValue, pref.partGetter(), pref.partSetter(), pref.summary) {
                override fun summary(
                    context: Context,
                    value: String?,
                ): String? = pref.summary(context, value)
            }
        }

        is AppClickablePreference<*> -> {
            AppClickablePreference<P>(title = title, summary = (this as AppClickablePreference<Q>).summary)
        }

        is AppDestinationPreference<*> -> {
            val pref = this as AppDestinationPreference<Q>
            AppDestinationPreference<P>(title = pref.title, summary = pref.summary, destination = pref.destination)
        }
    }
}

/**
 * The panel's content area: [PreferencePanel.WIDTH_DP] wide and as tall as the shown level's heading and rows, up to
 * [PreferencePanel.MAX_HEIGHT_DP] (the old `heightIn(max = 480.dp)` column, measured naturally). Leanback's lists
 * always fill the height they are given, so the rows' own height is read after each layout (when every row is laid
 * out) and the panel takes it before drawing.
 */
private class PreferencePanelFrame(
    context: Context,
    /** The view of the level on top (a closing level may still be fading out over it) */
    private val topLevel: () -> View?,
) : FrameLayout(context) {
    private val widthPx = context.dp(PreferencePanel.WIDTH_DP)
    private val maxHeightPx = context.dp(PreferencePanel.MAX_HEIGHT_DP)

    /** The height of the content, 0 while unknown (more rows than fit: the full height) */
    private var naturalHeight = 0
    private var skippedDraws = 0

    private val preDraw =
        ViewTreeObserver.OnPreDrawListener {
            val natural = measureNatural()
            if (natural != naturalHeight && skippedDraws < MAX_SKIPPED_DRAWS) {
                naturalHeight = natural
                skippedDraws++
                requestLayout()
                false
            } else {
                skippedDraws = 0
                true
            }
        }

    init {
        id = R.id.lb_settings_options_panel
        clipChildren = false
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val available =
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maxHeightPx
            } else {
                minOf(maxHeightPx, MeasureSpec.getSize(heightMeasureSpec))
            }
        val height = if (naturalHeight > 0) minOf(naturalHeight, available) else available
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    /** The shown level's height when all its rows are laid out, else 0 */
    private fun measureNatural(): Int {
        val grid = topGrid() ?: return 0
        val layoutManager = grid.layoutManager ?: return 0
        val count = grid.adapter?.itemCount ?: return 0
        var extent = 0
        if (count > 0) {
            if (layoutManager.findViewByPosition(0) == null || layoutManager.findViewByPosition(count - 1) == null) return 0
            var top = Int.MAX_VALUE
            var bottom = Int.MIN_VALUE
            for (i in 0 until layoutManager.childCount) {
                val child = layoutManager.getChildAt(i) ?: continue
                if ((child.layoutParams as? RecyclerView.LayoutParams)?.isItemRemoved == true) continue
                top = minOf(top, layoutManager.getDecoratedTop(child))
                bottom = maxOf(bottom, layoutManager.getDecoratedBottom(child))
            }
            if (bottom > top) extent = bottom - top
        }
        // The heading and anything else around the list keep their size; the list grows or shrinks
        return height - grid.height + grid.paddingTop + extent + grid.paddingBottom
    }

    /** The list of the level on top */
    private fun topGrid(): VerticalGridView? = topLevel()?.takeIf { it.parent === this }?.let(::findGrid)

    private fun findGrid(view: View): VerticalGridView? {
        if (view is VerticalGridView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findGrid(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private companion object {
        const val MAX_SKIPPED_DRAWS = 3
    }
}
