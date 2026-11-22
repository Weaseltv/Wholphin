package com.github.damontecres.wholphin.leanback.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import java.util.Locale

/*
 * Small private controls for the settings area (no-Compose UI, 2026-11-22). The shared CONTROLS widgets do not exist
 * in this checkout yet (runbooks/no-compose/CONTRACT.md section 3), so the settings panels use these: a text button,
 * an edit box and a confirmation dialog in the old look.
 */

/** Dialog and panel titles: Neon `dialogTitle` (Audiowide 26sp, upper case), otherwise `headlineSmall` */
internal fun TextView.styleDialogTitle(text: CharSequence) {
    if (SettingsLook.neon) {
        typeface = AppFonts.display(context)
        textSize = 26f
        letterSpacing = .06f
        this.text = text.toString().uppercase(Locale.getDefault())
    } else {
        applyTextRole(TextRole.HEADLINE_SMALL)
        this.text = text
    }
    setTextColor(AppTheme.palette.onSurface)
}

/**
 * A text button (the old `TextButton`): Neon is a square outline in `line2` that fills with `chipOn(accent)` and
 * takes the accent border when focused; other themes are tv-material3 pills (`surfaceVariant`, `onSurface` when
 * focused).
 */
internal fun settingsButton(
    context: Context,
    text: CharSequence,
    accent: Int,
    onClick: () -> Unit,
): TextView =
    AppCompatTextView(context).apply {
        this.text = text
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        minHeight = context.dp(40)
        minWidth = context.dp(96)
        setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        if (SettingsLook.neon) {
            applyTextRole(TextRole.LABEL_LARGE)
        } else {
            applyTextRole(TextRole.LABEL_LARGE)
        }
        val palette = AppTheme.palette
        val (rest, focused) =
            if (SettingsLook.neon) {
                NeonColors.TEXT to NeonColors.TEXT
            } else {
                palette.onSurfaceVariant to palette.inverseOnSurface
            }
        setTextColor(
            android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(),
                ),
                intArrayOf(disabledColor(rest), focused, rest),
            ),
        )
        background = buttonBackground(context, accent)
        setOnClickListener { onClick() }
    }

private fun buttonBackground(
    context: Context,
    accent: Int,
): Drawable {
    val palette = AppTheme.palette
    val radius = if (SettingsLook.neon) 0f else context.dpF(20f)
    val stroke = context.dp(1)

    fun shape(
        fill: Int,
        border: Int?,
    ) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
        border?.let { setStroke(stroke, it) }
    }
    return StateListDrawable().apply {
        if (SettingsLook.neon) {
            addState(intArrayOf(android.R.attr.state_focused), shape(NeonColors.chipOn(accent), accent))
            addState(intArrayOf(android.R.attr.state_pressed), shape(NeonColors.chipOn(accent), accent))
            addState(intArrayOf(), shape(NeonColors.CARD, NeonColors.LINE2))
        } else {
            addState(intArrayOf(android.R.attr.state_focused), shape(palette.onSurface, null))
            addState(intArrayOf(android.R.attr.state_pressed), shape(palette.onSurface, null))
            addState(intArrayOf(), shape(palette.surfaceVariant, null))
        }
    }
}

/** An edit box in the old `EditTextBox` look: a filled field with a border that takes the accent when focused */
internal fun settingsEditText(
    context: Context,
    accent: Int,
): EditText =
    AppCompatEditText(context).apply {
        applyTextRole(TextRole.BODY_LARGE)
        val palette = AppTheme.palette
        setTextColor(if (SettingsLook.neon) NeonColors.TEXT else palette.onSurface)
        setHintTextColor(if (SettingsLook.neon) NeonColors.LOW else palette.onSurfaceVariant)
        val radius = if (SettingsLook.neon) 0f else context.dpF(8f)
        val stroke = context.dp(1)

        fun shape(border: Int) =
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (SettingsLook.neon) NeonColors.WELL else SettingsLook.surfaceAt(3f))
                setStroke(stroke, border)
            }
        background =
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(accent))
                addState(intArrayOf(), shape(if (SettingsLook.neon) NeonColors.LINE2 else palette.borderVariant))
            }
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
    }

/**
 * A confirmation dialog (the old `ConfirmDialog`): title, optional body, Cancel and Confirm. A platform [Dialog], so
 * focus returns to the row that opened it when it closes.
 */
internal fun showConfirmDialog(
    context: Context,
    accent: Int,
    title: CharSequence,
    body: CharSequence?,
    bodyColor: Int? = null,
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit,
): Dialog {
    val dialog = SettingsDialog(context)
    val content = dialog.column
    content.addView(
        AppCompatTextView(context).apply { styleDialogTitle(title) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
    )
    if (!body.isNullOrBlank()) {
        val bodyView =
            AppCompatTextView(context).apply {
                SettingsLook.styleBody(this)
                text = body
                bodyColor?.let { setTextColor(it) }
            }
        content.addView(
            ScrollView(context).apply {
                isFocusable = false
                addView(bodyView)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = context.dp(12)
            },
        )
    }
    val buttons =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
    val cancel =
        settingsButton(context, context.getString(R.string.cancel), accent) {
            dialog.dismiss()
            onCancel()
        }
    val confirm =
        settingsButton(context, context.getString(R.string.confirm), accent) {
            dialog.dismiss()
            onConfirm()
        }
    buttons.addView(cancel)
    buttons.addView(
        confirm,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(16)
        },
    )
    content.addView(
        buttons,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(16)
        },
    )
    dialog.setOnCancelListener { onCancel() }
    dialog.show()
    // The old dialog opened on its confirm button
    confirm.requestFocus()
    return dialog
}

/**
 * A centred platform dialog with the panel surface (Neon `card` with a `line2` border, otherwise the 3dp surface,
 * 16dp corners) and the dim scrim behind it; [column] holds the content.
 */
internal class SettingsDialog(
    context: Context,
    widthDp: Int = 480,
) : Dialog(context) {
    val column: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(24)
            setPadding(pad, pad, pad, pad)
            background =
                GradientDrawable().apply {
                    if (SettingsLook.neon) {
                        setColor(NeonColors.CARD)
                        setStroke(context.dp(1), NeonColors.LINE2)
                    } else {
                        setColor(SettingsLook.surfaceAt(3f))
                        cornerRadius = context.dpF(16f)
                    }
                }
            clipChildren = false
        }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(column)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(.68f)
        }
    }
}

/** A long text that scrolls with UP/DOWN when focused (release notes, license text) */
internal fun scrollingText(
    context: Context,
    text: CharSequence,
): TextView =
    AppCompatTextView(context).apply {
        SettingsLook.styleBody(this)
        this.text = text
        movementMethod = ScrollingMovementMethod()
        isFocusable = true
        isVerticalScrollBarEnabled = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
