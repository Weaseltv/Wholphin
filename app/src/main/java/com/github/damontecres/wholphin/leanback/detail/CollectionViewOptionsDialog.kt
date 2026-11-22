package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.settings.SettingsPanelHeader
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.control.SliderBarView
import com.github.damontecres.wholphin.leanback.widget.control.SwitchWithLabelView
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuItem
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuListView
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsReset
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewOptions

/**
 * A collection's view options (CollectionViewOptionsDialog.kt in a `PreferenceOptionsDialog`): a 320dp panel at the
 * end of the screen (no dimming), at most 480dp tall, headed "View options": Separate types, then for one grid the
 * image type, aspect ratio, titles, columns, spacing, image scaling and Reset (for separate rows only titles and
 * Reset). Every change applies at once ([onViewOptionsChange]); Reset restores the defaults. Choices open a list with
 * the current value marked; sliders change with LEFT / RIGHT. The first row is focused first.
 */
class CollectionViewOptionsDialog(
    context: Context,
    initial: CollectionViewOptions,
    accent: Int,
    private val onViewOptionsChange: (CollectionViewOptions) -> Unit,
    private val defaultViewOptions: CollectionViewOptions = CollectionViewOptions(),
) : AppDialog(context, DialogOptions(usePlatformDefaultWidth = false, gravity = Gravity.END), accent) {
    private var options = initial
    private val rows =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val h = context.dp(16)
            val v = context.dp(MenuDialog.DIALOG_LIST_EDGE_DP)
            setPadding(h, v, h, v)
        }
    private val list = MenuListView(context).apply { addView(rows) }
    private val refreshers = mutableListOf<() -> Unit>()
    private var builtFor: Boolean? = null

    init {
        val column =
            object : LinearLayout(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    val maxH = context.dp(480)
                    val h =
                        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                            MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                        } else {
                            MeasureSpec.makeMeasureSpec(minOf(maxH, MeasureSpec.getSize(heightMeasureSpec)), MeasureSpec.AT_MOST)
                        }
                    super.onMeasure(MeasureSpec.makeMeasureSpec(context.dp(320), MeasureSpec.EXACTLY), h)
                }
            }
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.addView(
            SettingsPanelHeader(context, accent).apply { title = context.getString(R.string.view_options) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setPanelContent(column, FrameLayout.LayoutParams(context.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT))
        rebuild()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setDimAmount(0f)
    }

    override fun onStart() {
        super.onStart()
        rows.getChildAt(0)?.requestFocus()
    }

    private fun apply(new: CollectionViewOptions) {
        options = new
        onViewOptionsChange(new)
        if (builtFor != new.separateTypes) {
            rebuild()
            rows.getChildAt(0)?.requestFocus()
        } else {
            refreshers.forEach { it() }
        }
    }

    private fun rebuild() {
        rows.removeAllViews()
        refreshers.clear()
        builtFor = options.separateTypes
        val separate = CollectionViewOptions.SeparateTypes
        addSwitch(separate.title, { separate.getter(options) }) { apply(separate.setter(options, it)) }
        val prefs = if (options.separateTypes) CollectionViewOptions.SeparateOptions else CollectionViewOptions.MixedOptions
        prefs.forEach { pref ->
            @Suppress("UNCHECKED_CAST")
            val p = pref as AppPreference<ViewOptions, Any>
            addRow(p)
        }
        refreshers.forEach { it() }
    }

    private fun updateCard(transform: (ViewOptions) -> ViewOptions) = apply(options.copy(cardViewOptions = transform(options.cardViewOptions)))

    @Suppress("UNCHECKED_CAST")
    private fun addRow(pref: AppPreference<ViewOptions, Any>) {
        when (pref) {
            is AppSwitchPreference<*> -> {
                val sw = pref as AppSwitchPreference<ViewOptions>
                addSwitch(sw.title, { sw.getter(options.cardViewOptions) }) { v -> updateCard { sw.setter(it, v) } }
            }

            is AppChoicePreference<*, *> -> {
                val choice = pref as AppChoicePreference<ViewOptions, Any?>
                val labels = context.resources.getStringArray(choice.displayValues)
                val item =
                    AppListItem(context).apply {
                        setHeadline(context.getString(choice.title))
                        setOnClickListener {
                            val current = choice.valueToIndex(choice.getter(options.cardViewOptions))
                            MenuDialog.show(
                                context = context,
                                title = context.getString(choice.title),
                                entries =
                                    labels.mapIndexed { index, label ->
                                        MenuItem(
                                            headline = label,
                                            leading = ListLeading.SelectedDot(index == current),
                                            onClick = { updateCard { choice.setter(it, choice.indexToValue(index)) } },
                                        )
                                    },
                                dismissOnClick = true,
                                waitToLoad = false,
                                accent = this@CollectionViewOptionsDialog.accent,
                                opener = this,
                            )
                        }
                    }
                refreshers += {
                    val index = choice.valueToIndex(choice.getter(options.cardViewOptions))
                    item.setSupporting(labels.getOrNull(index))
                }
                addRowView(item)
            }

            is AppSliderPreference<*> -> {
                val slider = pref as AppSliderPreference<ViewOptions>
                val row = SliderRow(context, context.getString(slider.title), slider.min, slider.max, slider.interval) { v -> updateCard { slider.setter(it, v) } }
                refreshers += { row.value = slider.getter(options.cardViewOptions) }
                addRowView(row)
            }

            is AppClickablePreference<*> -> {
                val item =
                    AppListItem(context).apply {
                        setHeadline(context.getString(pref.title))
                        setOnClickListener {
                            if (pref == ViewOptionsReset) apply(defaultViewOptions)
                        }
                    }
                addRowView(item)
            }

            else -> {}
        }
    }

    private fun addSwitch(
        title: Int,
        value: () -> Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val sw =
            SwitchWithLabelView(context).apply {
                label.text = context.getString(title)
                onStateChange = { onChange(it) }
            }
        refreshers += { sw.checked = value() }
        addRowView(sw)
    }

    private fun addRowView(view: View) {
        rows.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A slider setting: its title and value over the bar (`SliderPreference`); LEFT / RIGHT change it */
    private class SliderRow(
        context: Context,
        title: String,
        min: Long,
        max: Long,
        interval: Int,
        onChange: (Long) -> Unit,
    ) : LinearLayout(context) {
        private val valueText =
            AppCompatTextView(context).apply {
                applyTextRole(TextRole.BODY_MEDIUM)
                setTextColor(AppTheme.palette.onSurface)
            }
        private val bar =
            SliderBarView(context).apply {
                this.min = min
                this.max = max
                this.interval = interval
                this.onChange = {
                    valueText.text = it.toString()
                    onChange(it)
                }
            }

        var value: Long
            get() = bar.value
            set(v) {
                bar.value = v
                valueText.text = v.toString()
            }

        init {
            orientation = VERTICAL
            clipChildren = false
            clipToPadding = false
            val h = context.dp(16)
            val v = context.dp(12)
            setPadding(h, v, h, v)
            addView(
                AppCompatTextView(context).apply {
                    text = title
                    applyTextRole(TextRole.TITLE_MEDIUM)
                    setTextColor(AppTheme.palette.onSurface)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(8) },
            )
            val line =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    addView(bar, LayoutParams(0, context.dp(24), 1f))
                    addView(valueText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(16) })
                }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
}
