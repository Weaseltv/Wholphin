package com.github.damontecres.wholphin.leanback.library

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.settings.SettingsLook
import com.github.damontecres.wholphin.leanback.settings.SettingsPanelHeader
import com.github.damontecres.wholphin.leanback.settings.SettingsSliderView
import com.github.damontecres.wholphin.leanback.settings.styleSwitch
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuItem
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsType

/**
 * The library View options (`ViewOptionsDialog` in `PreferenceOptionsDialog`): a 320dp panel at the end of the screen,
 * at most 480dp tall, without dimming the page, headed "View options", listing [ViewOptions.GRID_OPTIONS] (layout,
 * image type, aspect ratio, details, backdrop, titles, columns, spacing, content scale, reset) or, for the list layouts,
 * [ViewOptions.LIST_OPTIONS]. The rows look and behave like the settings rows: OK on a choice opens its choices,
 * OK on a switch toggles it, LEFT / RIGHT move a slider, Reset restores [defaultViewOptions]. Every change is passed to
 * [onViewOptionsChange] at once (the page saves it), and [update] shows the new value. The first row is focused.
 */
class LibraryViewOptionsDialog(
    context: Context,
    private var viewOptions: ViewOptions,
    private val defaultViewOptions: ViewOptions,
    accent: Int,
    private val onViewOptionsChange: (ViewOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    private val themed: Context = ContextThemeWrapper(context, R.style.lb_settings_PreferenceTheme)
    private val dialog =
        AppDialog(
            context,
            DialogOptions(usePlatformDefaultWidth = false, gravity = Gravity.END or Gravity.CENTER_VERTICAL),
            accent = accent,
        )
    private val accent = dialog.accent
    private val rows = LinearLayout(themed)
    private val scroller = MaxHeightScrollView(themed, themed.dp(480))
    private var shownType: ViewOptionsType? = null
    private val refreshers = mutableListOf<(ViewOptions) -> Unit>()
    private val keyRows = mutableMapOf<AppPreference<ViewOptions, *>, View>()

    init {
        val column =
            LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        column.addView(
            SettingsPanelHeader(themed, this.accent).apply { title = context.getString(R.string.view_options) },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        rows.orientation = LinearLayout.VERTICAL
        rows.clipChildren = false
        rows.clipToPadding = false
        val h = themed.dp(16)
        val v = themed.dp(DIALOG_LIST_EDGE_DP)
        rows.setPadding(h, v, h, v)
        scroller.isVerticalScrollBarEnabled = false
        scroller.clipChildren = false
        scroller.isFillViewport = false
        scroller.addView(rows, FrameLayout.LayoutParams(MATCH, WRAP))
        column.addView(scroller, LinearLayout.LayoutParams(MATCH, WRAP))
        dialog.setPanelContent(column, FrameLayout.LayoutParams(themed.dp(320), WRAP))
        dialog.onDismissRequest = onDismiss
        dialog.setOnShowListener { dialog.window?.setDimAmount(0f) }
        buildRows(focusFirst = true)
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()

    val isShowing: Boolean get() = dialog.isShowing

    /** The page saved a new value: show it (and switch the rows when the layout changed) */
    fun update(value: ViewOptions) {
        viewOptions = value
        if (value.type != shownType) {
            buildRows(focusFirst = false)
        } else {
            refreshers.forEach { it(value) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildRows(focusFirst: Boolean) {
        val focusedPref = keyRows.entries.firstOrNull { it.value.hasFocus() }?.key
        shownType = viewOptions.type
        rows.removeAllViews()
        refreshers.clear()
        keyRows.clear()
        val options =
            when (viewOptions.type) {
                ViewOptionsType.GRID -> ViewOptions.GRID_OPTIONS
                ViewOptionsType.LIST, ViewOptionsType.DENSE_LIST -> ViewOptions.LIST_OPTIONS
            }
        options.forEach { pref ->
            val row =
                when (pref) {
                    is AppSliderPreference -> sliderRow(pref as AppSliderPreference<ViewOptions>)
                    is AppSwitchPreference -> switchRow(pref as AppSwitchPreference<ViewOptions>)
                    is AppChoicePreference<*, *> -> choiceRow(pref as AppChoicePreference<ViewOptions, Any?>)
                    is AppClickablePreference -> clickRow(pref as AppClickablePreference<ViewOptions>)
                    else -> null
                } ?: return@forEach
            keyRows[pref as AppPreference<ViewOptions, *>] = row
            rows.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        refreshers.forEach { it(viewOptions) }
        val target = focusedPref?.let { keyRows[it] } ?: if (focusFirst) rows.getChildAt(0) else keyRows.values.firstOrNull()
        target?.let { view -> rows.post { view.requestFocus() } }
    }

    private fun inflateRow(layout: Int): View = LayoutInflater.from(themed).inflate(layout, rows, false)

    private fun styleRow(row: View) {
        (row.findViewById<View>(android.R.id.title) as? TextView)?.let(SettingsLook::styleTitle)
        (row.findViewById<View>(android.R.id.summary) as? TextView)?.let { SettingsLook.styleSummary(it, accent) }
        SettingsLook.decorateRow(row, accent)
    }

    /** `ClickPreference`: title and summary */
    private fun clickRow(pref: AppClickablePreference<ViewOptions>): View {
        val row = inflateRow(R.layout.lb_settings_row)
        styleRow(row)
        row.findViewById<TextView>(android.R.id.title).text = themed.getString(pref.title)
        val summary = row.findViewById<TextView>(android.R.id.summary)
        refreshers += { value ->
            val text = pref.summary(themed, null)
            summary.text = text
            summary.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<View>(android.R.id.widget_frame).visibility = View.GONE
        row.setOnClickListener {
            if (pref == ViewOptions.ViewOptionsReset) change(defaultViewOptions)
        }
        return row
    }

    /** `SwitchPreference`: OK toggles; the trailing switch shows the value */
    private fun switchRow(pref: AppSwitchPreference<ViewOptions>): View {
        val row = inflateRow(R.layout.lb_settings_row)
        styleRow(row)
        row.findViewById<TextView>(android.R.id.title).text = themed.getString(pref.title)
        val summary = row.findViewById<TextView>(android.R.id.summary)
        val frame = row.findViewById<LinearLayout>(android.R.id.widget_frame)
        val switch = LayoutInflater.from(themed).inflate(R.layout.lb_settings_widget_switch, frame, false) as SwitchCompat
        frame.addView(switch)
        frame.visibility = View.VISIBLE
        styleSwitch(switch, accent)
        refreshers += { value ->
            val checked = pref.getter(value)
            switch.isChecked = checked
            val text = pref.summary(themed, checked)
            summary.text = text
            summary.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        row.setOnClickListener { change(pref.setter(viewOptions, !pref.getter(viewOptions))) }
        return row
    }

    /** `ChoicePreference`: the current choice as summary; OK opens the choices (DialogPopup) with a dot on the chosen one */
    private fun choiceRow(pref: AppChoicePreference<ViewOptions, Any?>): View {
        val row = inflateRow(R.layout.lb_settings_row)
        styleRow(row)
        val title = themed.getString(pref.title)
        row.findViewById<TextView>(android.R.id.title).text = title
        val summary = row.findViewById<TextView>(android.R.id.summary)
        row.findViewById<View>(android.R.id.widget_frame).visibility = View.GONE
        val values = themed.resources.getStringArray(pref.displayValues).toList()
        val subtitles = pref.subtitles?.let { themed.resources.getStringArray(it).toList() }
        refreshers += { value ->
            val current = pref.getter(value)
            summary.text =
                pref.summary?.let(themed::getString)
                    ?: pref.summary(themed, current)
                    ?: values.getOrNull(pref.valueToIndex(current))
            summary.visibility = View.VISIBLE
        }
        row.setOnClickListener {
            val selected = pref.valueToIndex(pref.getter(viewOptions))
            MenuDialog.show(
                context = row.context,
                title = title,
                entries =
                    values.mapIndexed { index, label ->
                        MenuItem(
                            headline = label,
                            onClick = { change(pref.setter(viewOptions, pref.indexToValue(index))) },
                            supporting = subtitles?.getOrNull(index)?.takeIf { it.isNotBlank() },
                            leading = ListLeading.SelectedDot(index == selected),
                            dismissOnClick = true,
                        )
                    },
                dismissOnClick = false,
                waitToLoad = false,
                accent = accent,
            )
        }
        return row
    }

    /** `SliderPreference`: title, the bar and the value; LEFT / RIGHT change it at once (no wrap) */
    private fun sliderRow(pref: AppSliderPreference<ViewOptions>): View {
        val row = inflateRow(R.layout.lb_settings_slider_row)
        val title = row.findViewById<TextView>(android.R.id.title)
        val summary = row.findViewById<TextView>(android.R.id.summary)
        val slider = row.findViewById<SettingsSliderView>(R.id.lb_settings_slider)
        SettingsLook.styleTitle(title)
        SettingsLook.styleSummary(summary, accent)
        if (AppTheme.isNeon) {
            title.setTextColor(NeonColors.TEXT)
            summary.setTextColor(NeonColors.TEXT)
        } else {
            title.setTextColor(SettingsLook.titleColors())
            summary.setTextColor(SettingsLook.titleColors())
        }
        title.text = themed.getString(pref.title)
        row.background = SliderRowBackground(themed, accent)
        slider.accent = accent
        refreshers += { value ->
            val v = pref.getter(value)
            slider.setValue(v, pref.min, pref.max)
            summary.text = pref.summary(themed, v) ?: pref.summary?.let(themed::getString)
        }
        row.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                val value = pref.getter(viewOptions)
                val next =
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        (value - pref.interval).coerceAtLeast(pref.min)
                    } else {
                        (value + pref.interval).coerceAtMost(pref.max)
                    }
                if (next != value) change(pref.setter(viewOptions, next))
            }
            true
        }
        return row
    }

    private fun change(value: ViewOptions) {
        viewOptions = value
        onViewOptionsChange(value)
        update(value)
    }

    /** A ScrollView no taller than [maxHeightPx] (the old `heightIn(max = 480.dp)` column) */
    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int,
    ) : ScrollView(context) {
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }
    }

    /**
     * The slider row's background (SliderPreference): WeaselTV black with a 1dp accent border while focused; other
     * themes `inverseSurface` with 8dp corners while focused, transparent otherwise.
     */
    private class SliderRowBackground(
        context: Context,
        private val accent: Int,
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private var focused = false

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_focused)
            if (now == focused) return false
            focused = now
            invalidateSelf()
            return true
        }

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            if (AppTheme.isNeon) {
                fill.color = 0xFF000000.toInt()
                canvas.drawRect(rect, fill)
                if (focused) {
                    val w = density
                    border.strokeWidth = w
                    border.color = accent
                    rect.inset(w / 2f, w / 2f)
                    canvas.drawRect(rect, border)
                }
            } else if (focused) {
                fill.color = AppTheme.palette.inverseSurface
                canvas.drawRoundRect(rect, 8 * density, 8 * density, fill)
            }
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
