package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.RailAccents
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.preferences.AppThemeColors

/*
 * The settings rows on AndroidX Preference (no-Compose UI, 2026-11-22). Every row is a real androidx.preference
 * type shown by Leanback's preference list; these subclasses only add the WeaselPlex look (SettingsLook), long
 * press and focus callbacks. None of them persist: values are read from and written to the proto DataStore by
 * the screens (AppPreferenceBinder), exactly as the old PreferencesContent did.
 */

/** What every settings row shares: the page accent, long press and a focus callback */
internal interface SettingsRow {
    var accent: Int
    var onLongClick: (() -> Unit)?
    var onFocusChange: ((Boolean) -> Unit)?
}

/** Styles a bound row: type, colours, focus background, long press and focus callbacks */
internal fun PreferenceViewHolder.bindSettingsRow(row: SettingsRow) {
    (findViewById(android.R.id.title) as? TextView)?.let { SettingsLook.styleTitle(it, row.accent) }
    (findViewById(android.R.id.summary) as? TextView)?.let { SettingsLook.styleSummary(it, row.accent) }
    itemView.background = SettingsLook.rowBackground(itemView.context, row.accent)
    val neon = SettingsLook.neon
    itemView.setOnFocusChangeListener { v, hasFocus ->
        if (!neon) {
            val scale = if (hasFocus) SettingsLook.LIST_ITEM_FOCUS_SCALE else 1f
            v
                .animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(NeonColors.FOCUS_MS)
                .start()
        }
        row.onFocusChange?.invoke(hasFocus)
    }
    val longClick = row.onLongClick
    if (longClick != null) {
        itemView.setOnLongClickListener {
            longClick()
            true
        }
    } else {
        itemView.setOnLongClickListener(null)
        itemView.isLongClickable = false
    }
    if (itemView.isFocused) row.onFocusChange?.invoke(true)
}

private fun Preference.initRow() {
    layoutResource = R.layout.lb_settings_row
    isPersistent = false
    isIconSpaceReserved = false
    isSingleLineTitle = false
}

/** A row that does something when clicked (the old `ClickPreference`) */
internal open class SettingsClickPreference(
    context: Context,
) : Preference(context),
    SettingsRow {
    override var accent: Int = RailAccents.SETTINGS
    override var onLongClick: (() -> Unit)? = null
    override var onFocusChange: ((Boolean) -> Unit)? = null

    /** Called on OK; also available through [setOnPreferenceClickListener] */
    var onClick: (() -> Unit)? = null

    /** A short text at the end of the row (eg a version) */
    var trailingText: CharSequence? = null
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    init {
        initRow()
    }

    override fun onClick() {
        onClick?.invoke()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.bindSettingsRow(this)
        val frame = holder.findViewById(android.R.id.widget_frame) as? android.widget.LinearLayout ?: return
        val text = trailingText
        if (text != null) {
            frame.removeAllViews()
            frame.visibility = View.VISIBLE
            frame.addView(
                androidx.appcompat.widget.AppCompatTextView(frame.context).apply {
                    this.text = text
                    SettingsLook.styleSummary(this, accent)
                    isDuplicateParentStateEnabled = true
                },
            )
        } else if (widgetLayoutResource == 0) {
            frame.removeAllViews()
            frame.visibility = View.GONE
        }
    }
}

/** An on/off row with a trailing switch ([SettingsSwitchView], the old `SwitchPreference`) */
internal class SettingsSwitchPreference(
    context: Context,
) : SwitchPreferenceCompat(context),
    SettingsRow {
    override var accent: Int = RailAccents.SETTINGS
    override var onLongClick: (() -> Unit)? = null
    override var onFocusChange: ((Boolean) -> Unit)? = null

    init {
        initRow()
        widgetLayoutResource = R.layout.lb_settings_widget_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.bindSettingsRow(this)
        // The switch follows the row's focus (duplicateParentState in its layout) for the bold look's solid row
        (holder.findViewById(androidx.preference.R.id.switchWidget) as? SettingsSwitchView)?.let { styleSwitch(it, accent) }
    }
}

/**
 * The switch colours of the old `SwitchColors()`: Neon tracks `line2` / accent with `mid` / `onAccent` knobs,
 * stock colours elsewhere (the Material 3 thumbs in `onPrimary` on the GREEN to BROWN themes). The outline of an off
 * switch is the off track colour on the Neon theme and the theme `border` colour elsewhere (tv-material3 defaults).
 *
 * The Settings area's bold look uses the phone apps' switch (`NeonBoard.switchTrackOff/ThumbOn/ThumbOff`): an
 * accent track with a dark knob when on, a `#343A3E` track with a white knob when off. On the focused (solid accent)
 * row an on switch turns dark with an accent knob so it stays visible.
 */
internal fun styleSwitch(
    switch: SettingsSwitchView,
    accent: Int,
) {
    val palette = AppTheme.palette
    val (thumbOn, thumbOff, trackOn, trackOff) =
        when (AppTheme.theme) {
            AppThemeColors.WEASELTV -> {
                listOf(NeonColors.ON_ACCENT, NeonColors.MID, accent, NeonColors.LINE2)
            }

            AppThemeColors.GREEN, AppThemeColors.ORANGE, AppThemeColors.BOLD_BLUE, AppThemeColors.OLED_BLACK,
            AppThemeColors.RED, AppThemeColors.BROWN,
            -> {
                listOf(palette.onPrimary, palette.onPrimary, palette.primary, palette.surfaceVariant)
            }

            else -> {
                listOf(palette.onPrimary, palette.onSurfaceVariant, palette.primary, palette.surfaceVariant)
            }
        }
    switch.thumbOn = thumbOn
    switch.thumbOff = thumbOff
    switch.trackOn = trackOn
    switch.trackOff = trackOff
    switch.outlineOff = if (AppTheme.theme == AppThemeColors.WEASELTV) NeonColors.LINE2 else palette.border
    if (SettingsLook.bold(accent)) {
        switch.thumbOn = BOLD_SWITCH_THUMB_ON
        switch.thumbOff = BOLD_SWITCH_THUMB_OFF
        switch.trackOn = accent
        switch.trackOff = BOLD_SWITCH_TRACK_OFF
        switch.outlineOff = BOLD_SWITCH_TRACK_OFF
        switch.filledThumbOn = accent
        switch.filledTrackOn = BOLD_SWITCH_THUMB_ON
    } else {
        switch.filledThumbOn = null
        switch.filledTrackOn = null
    }
    switch.invalidate()
}

/** The phone apps' switch colours (`NeonBoard.switchThumbOn`, `switchThumbOff`, `switchTrackOff`) */
private const val BOLD_SWITCH_THUMB_ON = 0xFF090B0B.toInt()
private const val BOLD_SWITCH_THUMB_OFF = 0xFFFFFFFF.toInt()
private const val BOLD_SWITCH_TRACK_OFF = 0xFF343A3E.toInt()

/** A single choice row; the choices open in Leanback's list panel (the old `ChoicePreference` dialog) */
internal class SettingsListPreference(
    context: Context,
) : ListPreference(context),
    SettingsRow {
    override var accent: Int = RailAccents.SETTINGS
    override var onLongClick: (() -> Unit)? = null
    override var onFocusChange: ((Boolean) -> Unit)? = null

    init {
        initRow()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.bindSettingsRow(this)
    }
}

/** A multiple choice row; the choices open in Leanback's list panel with a check per choice */
internal class SettingsMultiListPreference(
    context: Context,
) : MultiSelectListPreference(context),
    SettingsRow {
    override var accent: Int = RailAccents.SETTINGS
    override var onLongClick: (() -> Unit)? = null
    override var onFocusChange: ((Boolean) -> Unit)? = null

    init {
        initRow()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.bindSettingsRow(this)
    }
}

/**
 * A text row; the text opens in the settings panel's editor ([SettingsEditTextFragment]: the field with Cancel and
 * Save, the old `StringInputDialog`).
 */
internal class SettingsEditTextPreference(
    context: Context,
) : EditTextPreference(context),
    SettingsRow {
    override var accent: Int = RailAccents.SETTINGS
    override var onLongClick: (() -> Unit)? = null
    override var onFocusChange: ((Boolean) -> Unit)? = null

    /** More than one line makes a multi-line editor (mpv.conf: 10) */
    var maxLines: Int = 1

    /** Leaving with changes asks to discard them first */
    var confirmDiscard: Boolean = false
    var inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    /** Runs before the editor opens, eg to read the current text from a file */
    var beforeOpen: (() -> Unit)? = null

    init {
        initRow()
    }

    override fun onClick() {
        beforeOpen?.invoke()
        super.onClick()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.bindSettingsRow(this)
    }
}

/**
 * A slider row (the old `SliderPreference` with its `SliderBar`): the title above the bar and its value beside it.
 * LEFT and RIGHT on the row move the value by [interval] between [min] and [max] (no wrap-around) and save it at
 * once; on the WeaselTV theme the row is black with a 1dp accent border when focused.
 */
internal class SettingsSliderPreference(
    context: Context,
) : Preference(context),
    SettingsRow {
    override var accent: Int = RailAccents.SETTINGS
    override var onLongClick: (() -> Unit)? = null
    override var onFocusChange: ((Boolean) -> Unit)? = null

    var min: Long = 0
    var max: Long = 100
    var interval: Int = 1

    /** Saves a new value; the screen refreshes [value] from the store afterwards */
    var onValueChange: ((Long) -> Unit)? = null
    var summarizer: ((Long) -> String?)? = null

    private var bound: PreferenceViewHolder? = null

    var value: Long = 0
        set(v) {
            if (field != v) {
                field = v
                updateBound()
            }
        }

    init {
        initRow()
        layoutResource = R.layout.lb_settings_slider_row
    }

    private fun updateBound() {
        val holder = bound ?: return
        (holder.findViewById(R.id.lb_settings_slider) as? SettingsSliderView)?.setValue(value, min, max)
        (holder.findViewById(android.R.id.summary) as? TextView)?.text = summarizer?.invoke(value) ?: summary
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bound = holder
        val neon = SettingsLook.neon
        val row = holder.itemView
        val title = holder.findViewById(android.R.id.title) as TextView
        val summaryView = holder.findViewById(android.R.id.summary) as TextView
        SettingsLook.styleTitle(title)
        SettingsLook.styleSummary(summaryView, accent)
        summaryView.visibility = View.VISIBLE
        if (neon) {
            // Neon: black slider rows, both texts `text`, the accent border while focused
            title.setTextColor(NeonColors.TEXT)
            summaryView.setTextColor(NeonColors.TEXT)
        } else {
            val colors = SettingsLook.titleColors()
            title.setTextColor(colors)
            summaryView.setTextColor(colors)
        }
        row.background = SettingsSliderBackground(row.context, accent)
        val slider = holder.findViewById(R.id.lb_settings_slider) as SettingsSliderView
        slider.accent = accent
        slider.setValue(value, min, max)
        summaryView.text = summarizer?.invoke(value) ?: summary
        row.contentDescription = this.title
        row.setOnFocusChangeListener { _, hasFocus -> onFocusChange?.invoke(hasFocus) }
        row.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN && isEnabled) {
                val next =
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        (value - interval).coerceAtLeast(min)
                    } else {
                        (value + interval).coerceAtMost(max)
                    }
                if (next != value) {
                    value = next
                    onValueChange?.invoke(next)
                }
            }
            true
        }
        if (row.isFocused) onFocusChange?.invoke(true)
    }

    override fun onDetached() {
        bound = null
        super.onDetached()
    }
}

/** A group heading (the old group `Text` above each group's rows) */
internal class SettingsCategory(
    context: Context,
) : PreferenceCategory(context) {
    /** The main settings panels' Neon 22sp bold headings; the other panels use `titleMedium` */
    var large: Boolean = true

    /** A fixed type role instead (the debug page's section titles) */
    var role: com.github.damontecres.wholphin.leanback.theme.TextRole? = null

    /** The page accent: the Settings area's headings take the bold look ([SettingsLook.bold]) */
    var accent: Int = RailAccents.SETTINGS

    init {
        layoutResource = R.layout.lb_settings_category
        isPersistent = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isFocusable = false
        if (this.title.isNullOrEmpty()) {
            holder.itemView.setPadding(0, 0, 0, 0)
            return
        }
        val title = holder.findViewById(android.R.id.title) as TextView
        val c = holder.itemView.context
        val big = large && SettingsLook.neon && role == null
        val fixedRole = role
        title.isAllCaps = false
        if (fixedRole != null) {
            title.applyTextRole(fixedRole)
            title.setTextColor(AppTheme.palette.onSurface)
        } else if (SettingsLook.bold(accent)) {
            SettingsLook.styleCategory(title, accent)
        } else if (big) {
            SettingsLook.styleCategory(title)
        } else {
            title.applyTextRole(com.github.damontecres.wholphin.leanback.theme.TextRole.TITLE_MEDIUM)
            title.setTextColor(AppTheme.palette.onSurface)
        }
        if (big) {
            holder.itemView.setPadding(c.dp(8), c.dp(12 + 8), c.dp(8), c.dp(12 + 4))
        } else {
            holder.itemView.setPadding(0, c.dp(8), 0, c.dp(4))
        }
        holder.itemView.isFocusable = false
    }
}

/** A row that only shows text (debug information, license details); focusable so the list can scroll through it */
internal class SettingsInfoPreference(
    context: Context,
) : SettingsClickPreference(context) {
    var textColor: Int? = null

    /** One line of information in `bodySmall` with little padding (the debug page's text) */
    var compact: Boolean = false

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val title = holder.findViewById(android.R.id.title) as? TextView
        if (compact && title != null) {
            title.applyTextRole(com.github.damontecres.wholphin.leanback.theme.TextRole.BODY_SMALL)
            title.maxLines = Int.MAX_VALUE
            val c = holder.itemView.context
            holder.itemView.minimumHeight = 0
            holder.itemView.setPadding(c.dp(8), c.dp(2), c.dp(8), c.dp(2))
        }
        textColor?.let { title?.setTextColor(it) }
    }
}

/** The slider row background: Neon black with the 1dp accent border when focused; stock `inverseSurface` 8dp */
private class SettingsSliderBackground(
    context: Context,
    accent: Int,
) : android.graphics.drawable.Drawable() {
    private val neon = SettingsLook.neon
    private val density = context.resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val stroke =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = density
            color = accent
        }
    private var focused = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
        if (f == focused) return false
        focused = f
        invalidateSelf()
        return true
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val r = android.graphics.RectF(bounds)
        if (neon) {
            fill.color = android.graphics.Color.BLACK
            canvas.drawRect(r, fill)
            if (focused) canvas.drawRect(r.left + density / 2, r.top + density / 2, r.right - density / 2, r.bottom - density / 2, stroke)
        } else if (focused) {
            fill.color = AppTheme.palette.inverseSurface
            canvas.drawRoundRect(r, 8 * density, 8 * density, fill)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Disabled text in the settings palette */
internal fun disabledColor(color: Int): Int = withAlpha(color, NeonColors.DISABLED_ALPHA)

/**
 * A search field row at the top of a level (the old `SearchEditTextBox` above the language list). Typing reports the
 * query through [onQuery]; the keyboard's search action calls [onSearch] (eg to move to the first result).
 */
internal class SettingsSearchPreference(
    context: Context,
) : Preference(context) {
    var accent: Int = RailAccents.SETTINGS
    var onQuery: ((String) -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    var query: String = ""
        private set

    init {
        layoutResource = R.layout.lb_settings_search_row
        isPersistent = false
        isSelectable = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isFocusable = false
        val field = holder.findViewById(R.id.lb_settings_search_field) as android.widget.EditText
        val styled = settingsEditText(field.context, accent)
        field.background = styled.background
        field.setTextColor(styled.textColors)
        field.setHintTextColor(styled.hintTextColors)
        field.typeface = styled.typeface
        field.textSize = styled.textSize / field.resources.displayMetrics.scaledDensity
        field.setPadding(styled.paddingLeft, styled.paddingTop, styled.paddingRight, styled.paddingBottom)
        field.isFocusable = true
        field.hint = context.getString(R.string.search)
        (field.getTag(R.id.lb_settings_search_field) as? android.text.TextWatcher)?.let(field::removeTextChangedListener)
        if (field.text.toString() != query) field.setText(query)
        val watcher =
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {}

                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString().orEmpty()
                    onQuery?.invoke(query)
                }
            }
        field.addTextChangedListener(watcher)
        field.setTag(R.id.lb_settings_search_field, watcher)
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                onSearch?.invoke()
                true
            } else {
                false
            }
        }
    }
}

/** A line between groups of rows (the old `HorizontalDivider`) */
internal class SettingsDividerPreference(
    context: Context,
) : Preference(context) {
    init {
        layoutResource = R.layout.lb_settings_divider
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isFocusable = false
        holder.findViewById(R.id.lb_settings_divider_line)?.setBackgroundColor(
            if (SettingsLook.neon) NeonColors.LINE2 else AppTheme.palette.borderVariant,
        )
    }
}
