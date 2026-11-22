package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.RailAccents
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF

/** Which part of a reorderable row has the focus; shared by the rows of a list so UP/DOWN keep the column */
internal class ReorderFocus {
    var zone: Int = 0
}

/** A small button at the end of a reorderable row */
internal data class ReorderButton(
    /** A Font Awesome glyph string (eg `R.string.fa_caret_up`) */
    val glyph: CharSequence,
    val enabled: Boolean,
    val description: String,
    val onClick: () -> Unit,
)

/**
 * A settings row with buttons at its end (the old side-menu pins and Home rows lists): the title, an optional
 * switch and buttons such as ▲ ▼ and delete. The row is one focusable Leanback Preference row; RIGHT and LEFT move
 * between its main part and its enabled buttons (disabled ones are skipped, as the old disabled buttons could not take
 * focus), OK runs the part that has focus, and UP/DOWN keep the same part on the next row ([focus] is shared).
 */
internal class SettingsReorderPreference(
    context: Context,
    private val focus: ReorderFocus,
) : Preference(context),
    SettingsRow {
    override var accent: Int = RailAccents.SETTINGS
    override var onLongClick: (() -> Unit)? = null
    override var onFocusChange: ((Boolean) -> Unit)? = null

    /** Shows a switch after the title when not null */
    var checked: Boolean? = null
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    var buttons: List<ReorderButton> = emptyList()
        set(value) {
            field = value
            notifyChanged()
        }

    /** OK on the main part */
    var onMainClick: (() -> Unit)? = null

    init {
        layoutResource = R.layout.lb_settings_row
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
    }

    private fun zones(): List<Int> = listOf(0) + buttons.indices.filter { buttons[it].enabled }.map { it + 1 }

    private fun currentZone(): Int {
        val zones = zones()
        if (focus.zone in zones) return focus.zone
        // The part that had focus is not here (eg ▲ on the first row): the nearest one before it
        return zones.lastOrNull { it < focus.zone } ?: 0
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val row = holder.itemView
        val ctx = row.context
        (holder.findViewById(android.R.id.title) as? TextView)?.let(SettingsLook::styleTitle)
        (holder.findViewById(android.R.id.summary) as? TextView)?.let { SettingsLook.styleSummary(it, accent) }
        val frame = holder.findViewById(android.R.id.widget_frame) as LinearLayout
        frame.removeAllViews()
        frame.visibility = View.VISIBLE
        checked?.let { isChecked ->
            val switch =
                SwitchCompat(ctx).apply {
                    this.isChecked = isChecked
                    isFocusable = false
                    isClickable = false
                    isDuplicateParentStateEnabled = false
                }
            styleSwitch(switch, accent)
            frame.addView(switch)
        }
        val buttonViews =
            buttons.mapIndexed { index, button ->
                AppCompatTextView(ctx).apply {
                    text = button.glyph
                    typeface = AppFonts.icons(ctx)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    contentDescription = button.description
                    isEnabled = button.enabled
                    background = buttonBackground(ctx)
                    setTextColor(buttonText())
                    frame.addView(
                        this,
                        LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)).apply {
                            marginStart = ctx.dp(if (index == 0) 8 else 4)
                        },
                    )
                }
            }
        val mainBackground = SettingsLook.rowBackground(ctx, accent)

        fun render(hasFocus: Boolean) {
            val zone = if (hasFocus) currentZone() else -1
            // The row background shows only while its main part has focus; a button shows its own focus
            row.background = if (zone == 0) mainBackground else null
            buttonViews.forEachIndexed { i, v -> v.isSelected = zone == i + 1 }
        }
        render(row.isFocused)
        row.setOnFocusChangeListener { _, hasFocus ->
            render(hasFocus)
            onFocusChange?.invoke(hasFocus)
        }
        row.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val zones = zones()
                        val at = zones.indexOf(currentZone())
                        val next = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) at + 1 else at - 1
                        if (next in zones.indices) {
                            focus.zone = zones[next]
                            render(true)
                        }
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
        row.setOnClickListener {
            when (val zone = currentZone()) {
                0 -> {
                    onMainClick?.invoke()
                }

                else -> {
                    buttons
                        .getOrNull(zone - 1)
                        ?.takeIf { it.enabled }
                        ?.onClick
                        ?.invoke()
                }
            }
        }
        val longClick = onLongClick
        if (longClick != null) {
            row.setOnLongClickListener {
                longClick()
                true
            }
        } else {
            row.setOnLongClickListener(null)
            row.isLongClickable = false
        }
    }

    /** The old 32dp move/delete buttons: Neon outlines that fill with `chipOn(accent)` when they have the focus */
    private fun buttonBackground(ctx: Context): StateListDrawable {
        val palette = AppTheme.palette
        val radius = if (SettingsLook.neon) 0f else ctx.dpF(16f)

        fun shape(
            fill: Int,
            border: Int?,
        ) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            border?.let { setStroke(ctx.dp(1), it) }
        }
        return StateListDrawable().apply {
            if (SettingsLook.neon) {
                addState(intArrayOf(android.R.attr.state_selected), shape(NeonColors.chipOn(accent), accent))
                addState(intArrayOf(-android.R.attr.state_enabled), shape(NeonColors.CARD, NeonColors.LINE))
                addState(intArrayOf(), shape(NeonColors.CARD, NeonColors.LINE2))
            } else {
                addState(intArrayOf(android.R.attr.state_selected), shape(palette.onSurface, null))
                addState(intArrayOf(), shape(palette.surfaceVariant, null))
            }
        }
    }

    private fun buttonText(): ColorStateList {
        val palette = AppTheme.palette
        val rest = if (SettingsLook.neon) NeonColors.TEXT else palette.onSurfaceVariant
        val focused = if (SettingsLook.neon) NeonColors.TEXT else palette.inverseOnSurface
        return ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf(),
            ),
            intArrayOf(disabledColor(rest), focused, rest),
        )
    }
}
