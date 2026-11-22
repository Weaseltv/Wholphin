package com.github.damontecres.wholphin.leanback.widget.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.withAlpha

/**
 * A text field for TV entry (EditTextBox.kt as a View): an [editText] in a decorated row with an optional leading
 * icon, 8dp padding, at least 280dp wide.
 *
 * Look (both themes): the surfaceVariant fill at 60 % (80 % focused, 25 % disabled), the error container at 75 % when
 * [isInputValid] fails; the Neon theme adds a 1dp square border (`line2`, the accent focused, error colour when
 * invalid); other themes are pill shaped (8dp corners with several lines). Text bodyLarge; [Variant.STATE] (the
 * `TextFieldState` overload) uses onSurfaceVariant, [Variant.VALUE] (the `value` overload) onSurface and a minimum
 * [heightDp] (40dp).
 *
 * Focus and keys, as before: focusing the field with the D-pad shows the soft keyboard (Compose text fields did;
 * [showKeyboardOnFocus]). The field reports itself to [ControlTrackers] while focused and editable. IME actions go
 * to [onImeAction]. [SearchEditTextBox] adds the search icon and D-pad rules.
 */
open class EditTextBox
    @JvmOverloads
    constructor(
        context: Context,
        val variant: Variant = Variant.STATE,
    ) : LinearLayout(context) {
        enum class Variant {
            /** The `TextFieldState` overload: onSurfaceVariant text, natural height */
            STATE,

            /** The `value` / `onValueChange` overload: onSurface text, [heightDp] minimum height */
            VALUE,
        }

        val editText: AppCompatEditText =
            AppCompatEditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                applyTextRole(TextRole.BODY_LARGE)
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            }

        val leadingIconView: ImageView = ImageView(context).apply { visibility = View.GONE }

        private val box = BoxDrawable()
        private val token = Any()

        /** Red / error look while this returns false for the current text */
        var isInputValid: (String) -> Boolean = { true }
            set(value) {
                field = value
                refresh()
            }

        /** Not editable but focusable (Compose `readOnly`) */
        var readOnly: Boolean = false
            set(value) {
                field = value
                editText.showSoftInputOnFocus = !value
                editText.isCursorVisible = !value
                if (value) editText.keyListener = null
                refresh()
            }

        /** Drawn disabled and not focusable (Compose `enabled = false` removed the field from focus) */
        var isFieldEnabled: Boolean = true
            set(value) {
                field = value
                editText.isEnabled = value
                refresh()
            }

        /** Show the soft keyboard when the field gains focus (Compose's default) */
        var showKeyboardOnFocus: Boolean = true

        /** [Variant.VALUE] minimum height in dp */
        var heightDp: Float = 40f
            set(value) {
                field = value
                updateMinHeight()
            }

        /** Called with the text after every change */
        var onTextChanged: ((String) -> Unit)? = null

        /** IME action (`EditorInfo.IME_ACTION_*`); return true when handled */
        var onImeAction: ((Int) -> Boolean)? = null

        private var accent: Int = NeonColors.VOLT

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box
            val p = context.dp(8)
            setPadding(p, p, p, p)
            minimumWidth = context.dp(280)
            addView(leadingIconView, LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(8) })
            addView(editText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            editText.setOnFocusChangeListener { _, focused ->
                refresh()
                ControlTrackers.textField(token, focused && !readOnly && isFieldEnabled)
                if (focused && showKeyboardOnFocus && !readOnly) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(editText, 0)
                }
            }
            editText.addTextChangedListener(
                object : TextWatcher {
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

                    override fun afterTextChanged(s: Editable?) {
                        refresh()
                        onTextChanged?.invoke(s?.toString() ?: "")
                    }
                },
            )
            editText.setOnEditorActionListener { _, actionId, event ->
                val action =
                    if (actionId == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                        editText.imeOptions and EditorInfo.IME_MASK_ACTION
                    } else {
                        actionId
                    }
                onImeAction?.invoke(action) ?: false
            }
            updateMinHeight()
        }

        var text: String
            get() = editText.text?.toString() ?: ""
            set(value) {
                if (value != text) {
                    editText.setText(value)
                    editText.setSelection(value.length)
                }
            }

        /** Placeholder text shown while empty (the `value` overload's `placeholder`) */
        var placeholder: CharSequence?
            get() = editText.hint
            set(value) {
                editText.hint = value
            }

        /** Several lines (the state overload's `maxLines`): rounded 8dp corners off the Neon theme */
        var maxLines: Int = 1
            set(value) {
                field = value
                editText.isSingleLine = value == 1
                editText.maxLines = value
                if (value > 1) editText.minLines = value
                refresh()
            }

        /** Password entry (dots instead of characters) */
        fun setPassword(password: Boolean) {
            editText.inputType =
                if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            editText.typeface = editText.typeface
        }

        /** A leading icon (eg the search glyph), tinted [tint] */
        fun setLeadingIcon(
            res: Int?,
            tint: Int? = null,
        ) {
            if (res == null) {
                leadingIconView.visibility = View.GONE
                return
            }
            leadingIconView.setImageResource(res)
            tint?.let { leadingIconView.imageTintList = ColorStateList.valueOf(it) }
            leadingIconView.visibility = View.VISIBLE
        }

        private fun updateMinHeight() {
            minimumHeight = if (variant == Variant.VALUE) context.dp(heightDp) else 0
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            accent = resolveNeonScope().accentOrDefault
            if (Build.VERSION.SDK_INT >= 29) {
                editText.textCursorDrawable =
                    GradientDrawable().apply {
                        setColor(if (AppTheme.isNeon) accent else AppTheme.palette.primary)
                        setSize(context.dp(2), 0)
                    }
            }
            refresh()
        }

        override fun onDetachedFromWindow() {
            ControlTrackers.textField(token, false)
            super.onDetachedFromWindow()
        }

        private fun refresh() {
            val p = AppTheme.palette
            val valid = isInputValid(text)
            val focused = editText.isFocused
            val fill =
                when {
                    !valid -> withAlpha(p.errorContainer, .75f)
                    focused && isFieldEnabled -> withAlpha(p.surfaceVariant, .8f)
                    !isFieldEnabled -> withAlpha(p.surfaceVariant, .25f)
                    else -> withAlpha(p.surfaceVariant, .6f)
                }
            val textColor =
                when {
                    !valid -> p.onErrorContainer
                    !isFieldEnabled -> withAlpha(p.onSurfaceVariant, .25f)
                    variant == Variant.VALUE -> p.onSurface
                    else -> p.onSurfaceVariant
                }
            editText.setTextColor(textColor)
            editText.setHintTextColor(withAlpha(textColor, .6f))
            box.fill = fill
            box.border =
                if (AppTheme.isNeon) {
                    when {
                        !valid -> p.error
                        focused -> accent
                        else -> NeonColors.LINE2
                    }
                } else {
                    0
                }
            box.cornerDp =
                when {
                    AppTheme.isNeon -> 0f
                    variant == Variant.STATE && maxLines > 1 -> 8f
                    else -> PILL
                }
            box.invalidateSelf()
        }

        /** The decorated container: fill and (Neon) 1dp border */
        private inner class BoxDrawable : Drawable() {
            var fill = 0
            var border = 0
            var cornerDp = 0f
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            private val rect = RectF()

            override fun draw(canvas: Canvas) {
                rect.set(bounds)
                val density = resources.displayMetrics.density
                val r = if (cornerDp == PILL) rect.height() / 2f else cornerDp * density
                paint.color = fill
                canvas.drawRoundRect(rect, r, r, paint)
                if ((border ushr 24) != 0) {
                    stroke.strokeWidth = density
                    stroke.color = border
                    rect.inset(density / 2f, density / 2f)
                    canvas.drawRoundRect(rect, r, r, stroke)
                }
            }

            override fun setAlpha(alpha: Int) {}

            override fun setColorFilter(colorFilter: ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        companion object {
            private const val PILL = -1f
        }
    }

/**
 * The search field (`SearchEditTextBox`): an [EditTextBox] with the search icon (the accent on the Neon theme,
 * onPrimaryContainer elsewhere), no autocorrect and the Search IME action, which hides the keyboard and calls
 * [onSearch]. The [EditTextBox.Variant.VALUE] variant is 56dp tall on the Neon theme (the search strip), 40dp elsewhere.
 *
 * D-pad: UP and DOWN always move focus out of the field (to the results / the controls above); LEFT and RIGHT move
 * focus only when [readOnly], otherwise they move the cursor.
 */
class SearchEditTextBox
    @JvmOverloads
    constructor(
        context: Context,
        variant: EditTextBox.Variant = EditTextBox.Variant.VALUE,
    ) : EditTextBox(context, variant) {
        /** Called by the Search IME action */
        var onSearch: (() -> Unit)? = null

        init {
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editText.imeOptions = EditorInfo.IME_ACTION_SEARCH
            if (variant == EditTextBox.Variant.VALUE) heightDp = if (AppTheme.isNeon) 56f else 40f
            onImeAction = { action ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(editText.windowToken, 0)
                    onSearch?.invoke()
                    true
                } else {
                    false
                }
            }
            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val direction =
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (readOnly) View.FOCUS_LEFT else return@setOnKeyListener false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (readOnly) View.FOCUS_RIGHT else return@setOnKeyListener false
                        else -> return@setOnKeyListener false
                    }
                editText.focusSearch(direction)?.requestFocus(direction) == true
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            val tint = if (AppTheme.isNeon) resolveNeonScope().accentOrDefault else AppTheme.palette.onPrimaryContainer
            setLeadingIcon(R.drawable.lb_widget_ctl_ic_search, tint)
        }
    }
