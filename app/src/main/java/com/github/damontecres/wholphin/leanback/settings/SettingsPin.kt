package com.github.damontecres.wholphin.leanback.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.leanback.settings.SettingsLook.setFont
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.ui.preferences.PinFlowState
import com.github.damontecres.wholphin.ui.preferences.PinFlowStep
import com.github.damontecres.wholphin.ui.preferences.pinFlowStep
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/*
 * PIN entry for Settings (no-Compose UI, 2026-11-22): a private View version of the old `PinEntryDialog` and
 * `SetPinFlow`. SETUP owns the shared PIN screens; Settings keeps its own so neither area waits for the other
 * (runbooks/no-compose/CONTRACT.md section 1).
 */

/** What a submitted PIN turned out to be; wrong entries count towards the forgot-PIN hint (contract 11.2) */
internal enum class SettingsPinOutcome { WRONG, OTHER }

internal fun PinCheckResult.settingsOutcome(): SettingsPinOutcome =
    if (this == PinCheckResult.Correct) SettingsPinOutcome.OTHER else SettingsPinOutcome.WRONG

/** The PIN character a remote key stands for, or null (OK never is) */
private fun pinPress(keyCode: Int): Char? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> 'U'
        KeyEvent.KEYCODE_DPAD_RIGHT -> 'R'
        KeyEvent.KEYCODE_DPAD_DOWN -> 'D'
        KeyEvent.KEYCODE_DPAD_LEFT -> 'L'
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        else -> null
    }

private fun isEnterKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

/** The kids/PIN panel colours (`ui/kids/KidPanel.kt` `KidColors`) */
private object PinColors {
    const val LIME = NeonColors.VOLT
    const val PANEL = 0xFF0C0F14.toInt()
    const val PANEL_EDGE = 0xFF1F242C.toInt()
    const val TILE = 0xFF12151C.toInt()
    const val TILE_EDGE = 0xFF363C46.toInt()
    const val LIME_FILL = 0xFF161E08.toInt()
    const val TEXT = NeonColors.TEXT
    const val ERROR = 0xFFFF6B6B.toInt()
}

/** "Enter **name's** PIN to continue" with the name in bold */
internal fun pinPromptFor(
    context: Context,
    name: String?,
): CharSequence? {
    if (name.isNullOrBlank()) return null
    val text = context.getString(R.string.pin_prompt_name, name)
    val part = "$name's"
    return SpannableString(text).apply {
        val start = text.indexOf(part)
        if (start >= 0) setSpan(StyleSpan(Typeface.BOLD), start, start + part.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** The website page where a profile's PIN is reset, or null without the WeaselPlex website */
private fun forgotPinUrl(): String? =
    BuildConfig.WEASELPLEX_WEBSITE_URL
        .takeIf { BuildConfig.KID_PROFILES_ENABLED && it.isNotBlank() }
        ?.let { "${it.trimEnd('/')}/weaselplex/profiles" }

/** A QR code for [content], black on white so a phone camera reads it off a TV */
internal fun settingsQrCode(
    content: String,
    pixels: Int = 512,
): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val colors = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) colors[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(colors, matrix.width, matrix.height, Bitmap.Config.RGB_565)
}

/**
 * The PIN prompt in its panel dialog (the old `PinEntryDialog`, owner mockup `4-pin.png`): the title, the prompt,
 * [length] boxes, a message line, the D-pad hint (or "Forgot your PIN?" with its QR code after repeated wrong entries)
 * and the OK / BACK key hints.
 *
 * Presses after the [length]th are ignored; the [length]th submits on its own and OK submits what is typed. Only a
 * press that started in the dialog counts, so the OK that opened it never submits it. While [onSubmit] runs every
 * key is ignored (the latch), then the boxes clear; keys are ignored until [lockedUntilMillis] too. BACK dismisses.
 */
internal class SettingsPinDialog(
    context: Context,
    private val scope: CoroutineScope,
    private val onSubmit: suspend (String) -> SettingsPinOutcome,
    onDismiss: () -> Unit,
) : Dialog(context) {
    private val titleView = AppCompatTextView(context)
    private val promptView = AppCompatTextView(context)
    private val boxes = LinearLayout(context)
    private val messageView = AppCompatTextView(context)
    private val hintFrame = FrameLayout(context)
    private val dpadHint = dpadHint(context)
    private val forgotHint: View? = forgotPinUrl()?.let { forgotHint(context, it) }

    private var input = ""
    private var pressed: Int? = null
    private var busy = false
    private var failed = false
    private var wrongEntries = 0
    private var submitJob: Job? = null

    var length: Int = PinRules.LENGTH
        set(value) {
            field = value
            renderBoxes()
        }

    /** False while choosing a new PIN, which is arrows only */
    var allowDigits: Boolean = true
    var lockedUntilMillis: Long = 0L

    var pinTitle: CharSequence? = context.getString(R.string.enter_pin)
        set(value) {
            field = value
            titleView.text = value
            titleView.textSize = if ((value?.length ?: 0) > 22) 24f else 32f
            titleView.visibility = if (value == null) View.GONE else View.VISIBLE
        }

    var prompt: CharSequence? = null
        set(value) {
            field = value
            promptView.text = value
            promptView.visibility = if (value == null) View.GONE else View.VISIBLE
        }

    var message: CharSequence? = null
        set(value) {
            field = value
            renderMessage()
        }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(context.dp(24), context.dp(28), context.dp(24), context.dp(24))
                background =
                    GradientDrawable().apply {
                        setColor(PinColors.PANEL)
                        setStroke(context.dp(1), PinColors.PANEL_EDGE)
                    }
            }
        titleView.apply {
            typeface = AppFonts.display(context)
            letterSpacing = .06f
            setTextColor(PinColors.TEXT)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        promptView.apply {
            setFont(19f, 400, 1.4f)
            setTextColor(PinColors.TEXT)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        messageView.apply {
            setFont(15f, 500, 1.4f)
            setTextColor(PinColors.ERROR)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        boxes.orientation = LinearLayout.HORIZONTAL
        column.addView(titleView, wrap())
        column.addView(promptView, wrap(top = 10))
        column.addView(boxes, wrap(top = 22))
        column.addView(messageView, wrap(top = 12))
        hintFrame.addView(
            dpadHint,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        forgotHint?.let {
            it.visibility = View.GONE
            hintFrame.addView(
                it,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
        column.addView(hintFrame, wrap(top = 18))
        column.addView(
            View(context).apply { setBackgroundColor(PinColors.PANEL_EDGE) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)).apply { topMargin = context.dp(18) },
        )
        column.addView(keyHints(context), wrap(top = 16))
        setContentView(column)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.dp(590), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(.68f)
        }
        column.isFocusable = true
        column.isFocusableInTouchMode = true
        setOnDismissListener {
            submitJob?.cancel()
            onDismiss()
        }
        pinTitle = context.getString(R.string.enter_pin)
        renderBoxes()
        renderMessage()
    }

    private fun wrap(top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(top)
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun accepting(): Boolean = !busy && System.currentTimeMillis() >= lockedUntilMillis

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isPinKey = pinPress(code) != null
        if (!isPinKey && !isEnterKey(code)) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (pressed == null && event.repeatCount == 0) pressed = code
            }

            KeyEvent.ACTION_UP -> {
                if (pressed == code) {
                    pressed = null
                    // A number key while choosing a new PIN isn't a press at all
                    val press = pinPress(code)?.takeIf { allowDigits || it in PinRules.ARROWS }
                    when {
                        !accepting() -> {}

                        isPinKey -> {
                            if (press != null) {
                                val next = PinRules.append(input, press, length)
                                if (PinRules.isComplete(next, length) && next != input) {
                                    submit(next)
                                } else {
                                    input = next
                                    renderBoxes()
                                }
                            }
                        }

                        input.isNotEmpty() -> {
                            submit(input)
                        }
                    }
                }
            }
        }
        return true
    }

    private fun submit(pin: String) {
        if (!accepting()) return
        busy = true
        failed = false
        input = pin
        renderBoxes()
        submitJob =
            scope.launch {
                try {
                    if (onSubmit(pin) == SettingsPinOutcome.WRONG) wrongEntries++
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "PIN check failed")
                    failed = true
                } finally {
                    input = ""
                    busy = false
                    if (isShowing) {
                        renderBoxes()
                        renderMessage()
                    }
                }
            }
    }

    private fun renderMessage() {
        val shown = if (failed) context.getString(R.string.kids_error_generic) else message
        messageView.text = shown
        messageView.visibility = if (shown == null) View.GONE else View.VISIBLE
        val forgot = forgotHint != null && PinRules.offersForgotPin(wrongEntries)
        forgotHint?.visibility = if (forgot) View.VISIBLE else View.GONE
        dpadHint.visibility = if (forgot) View.GONE else View.VISIBLE
    }

    /** Four 75 x 92dp boxes; a longer PIN saved before the 4-press rule gets narrower ones */
    private fun renderBoxes() {
        boxes.removeAllViews()
        val ctx = context
        val width = if (length <= 5) ctx.dp(75) else maxOf(ctx.dp(28), ctx.dp(440) / length)
        val gap = if (length <= 5) ctx.dp(18) else ctx.dp(8)
        val filled = input.length
        repeat(length) { index ->
            val current = index == filled
            val box =
                FrameLayout(ctx).apply {
                    background =
                        GradientDrawable().apply {
                            setColor(if (current) PinColors.LIME_FILL else PinColors.TILE)
                            setStroke(ctx.dpF(if (current) 2.5f else 1.5f).toInt(), if (current) PinColors.LIME else PinColors.TILE_EDGE)
                        }
                    if (current) {
                        elevation = ctx.dpF(8f)
                        outlineSpotShadowColor = PinColors.LIME
                        outlineAmbientShadowColor = PinColors.LIME
                    }
                }
            when {
                index < filled -> {
                    box.addView(
                        View(ctx).apply {
                            background =
                                GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(PinColors.TEXT)
                                }
                        },
                        FrameLayout.LayoutParams(ctx.dp(17), ctx.dp(17), Gravity.CENTER),
                    )
                }

                current -> {
                    box.addView(
                        View(ctx).apply { setBackgroundColor(PinColors.LIME) },
                        FrameLayout.LayoutParams(ctx.dp(3), ctx.dp(40), Gravity.CENTER),
                    )
                }
            }
            boxes.addView(
                box,
                LinearLayout.LayoutParams(width, ctx.dp(92)).apply { if (index > 0) marginStart = gap },
            )
        }
    }

    private fun dpadHint(context: Context): View {
        fun key(
            text: String,
            glyph: Boolean,
        ): TextView =
            AppCompatTextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextColor(PinColors.TEXT)
                if (glyph) {
                    typeface = AppFonts.icons(context)
                    textSize = 15f
                } else {
                    setFont(14f, 700, 1f)
                }
                background =
                    GradientDrawable().apply {
                        setColor(PinColors.TILE)
                        setStroke(context.dp(1), PinColors.TILE_EDGE)
                        cornerRadius = context.dpF(8f)
                    }
            }

        fun LinearLayout.addKey(view: View) {
            addView(
                view,
                LinearLayout.LayoutParams(context.dp(42), context.dp(42)).apply {
                    setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
                },
            )
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addKey(key(context.getString(R.string.fa_arrow_up_long), true))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addKey(key(context.getString(R.string.fa_arrow_left_long), true))
                    addKey(key(context.getString(R.string.kid_key_ok), false))
                    addKey(key(context.getString(R.string.fa_arrow_right_long), true))
                },
            )
            addKey(key(context.getString(R.string.fa_arrow_down_long), true))
        }
    }

    private fun forgotHint(
        context: Context,
        url: String,
    ): View {
        val shown = url.removePrefix("https://").removePrefix("http://")
        val text = context.getString(R.string.forgot_pin_hint, shown)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(context).apply { setImageBitmap(settingsQrCode(url)) },
                LinearLayout.LayoutParams(context.dp(112), context.dp(112)),
            )
            addView(
                AppCompatTextView(context).apply {
                    setFont(16f, 400, 1.4f)
                    setTextColor(PinColors.TEXT)
                    this.text =
                        SpannableString(text).apply {
                            val start = text.indexOf(shown)
                            if (start >= 0) {
                                setSpan(StyleSpan(Typeface.BOLD), start, start + shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                setSpan(ForegroundColorSpan(PinColors.LIME), start, start + shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                },
                LinearLayout.LayoutParams(context.dp(300), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(18) },
            )
        }
    }

    private fun keyHints(context: Context): View {
        fun hint(
            key: String,
            label: String,
        ): View =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    AppCompatTextView(context).apply {
                        text = key
                        setFont(12f, 700, 1.2f)
                        letterSpacing = .06f
                        setTextColor(PinColors.TEXT)
                        setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
                        background =
                            GradientDrawable().apply {
                                setStroke(context.dp(1), PinColors.TILE_EDGE)
                                cornerRadius = context.dpF(6f)
                            }
                    },
                )
                addView(
                    AppCompatTextView(context).apply {
                        text = label
                        setFont(14f, 400, 1.3f)
                        setTextColor(PinColors.TEXT)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = context.dp(8)
                    },
                )
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hint(context.getString(R.string.kid_key_ok), context.getString(R.string.confirm)))
            addView(
                hint(context.getString(R.string.kid_key_back), context.getString(R.string.cancel)),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dp(28)
                },
            )
        }
    }
}

/**
 * Set, change or remove a profile PIN (the old `SetPinFlow`): enter the current PIN (checked by [checkCurrentPin], with
 * the gate's backoff), then remove it, or choose a new 4-press PIN (arrows only) and confirm it. A mismatch or a failed
 * save says so and asks for the new PIN again. With [allowRemove] false (a kid-protected TV) the current PIN leads to
 * choosing a new one.
 */
internal class SettingsSetPinFlow(
    private val context: Context,
    scope: CoroutineScope,
    private val currentPin: String?,
    private val onAddPin: suspend (String) -> Boolean,
    private val onRemovePin: () -> Unit,
    private val onDismiss: () -> Unit,
    private val allowRemove: Boolean = true,
    private val checkCurrentPin: (suspend (String) -> PinCheckResult)? = null,
    private val fixedTitle: String? = null,
) {
    private var state = if (currentPin.isNullOrBlank()) PinFlowState.ENTER_PIN else PinFlowState.ENTER_PIN_TO_REMOVE
    private var pendingPin = ""
    private var lockedUntil = 0L
    private var finished = false
    private val dialog = SettingsPinDialog(context, scope, ::onSubmit) { if (!finished) onDismiss() }

    fun show() {
        render(null)
        dialog.show()
    }

    /** Closes the flow without calling the dismiss callback */
    private fun finish() {
        finished = true
        dialog.dismiss()
    }

    private fun render(message: String?) {
        dialog.pinTitle =
            fixedTitle ?: context.getString(if (state == PinFlowState.CONFIRM_PIN) R.string.confirm_pin else R.string.enter_pin)
        dialog.prompt =
            context.getString(
                when (state) {
                    PinFlowState.ENTER_PIN_TO_REMOVE -> R.string.pin_prompt_current
                    PinFlowState.ENTER_PIN -> R.string.pin_prompt_new
                    PinFlowState.CONFIRM_PIN -> R.string.pin_prompt_confirm
                },
            )
        dialog.length = if (state == PinFlowState.ENTER_PIN_TO_REMOVE) PinRules.entryLength(currentPin) else PinRules.LENGTH
        // A new PIN is arrows only; the current one is checked as saved
        dialog.allowDigits = state == PinFlowState.ENTER_PIN_TO_REMOVE
        dialog.lockedUntilMillis = if (state == PinFlowState.ENTER_PIN_TO_REMOVE) lockedUntil else 0L
        dialog.message = message
    }

    private fun goTo(
        next: PinFlowState,
        line: String? = null,
    ) {
        state = next
        render(line)
    }

    private suspend fun onSubmit(typed: String): SettingsPinOutcome {
        if (state == PinFlowState.ENTER_PIN_TO_REMOVE && checkCurrentPin != null) {
            // Suspends for the gate's check, so the entry stays latched until it is done
            val result = checkCurrentPin.invoke(typed)
            when (result) {
                PinCheckResult.Correct -> {
                    if (allowRemove) {
                        finish()
                        onRemovePin()
                    } else {
                        goTo(PinFlowState.ENTER_PIN)
                    }
                }

                is PinCheckResult.Wrong -> {
                    dialog.message = context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)
                }

                is PinCheckResult.LockedOut -> {
                    val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                    lockedUntil = result.retryAtMillis
                    dialog.lockedUntilMillis = lockedUntil
                    dialog.message = context.getString(R.string.kid_pin_locked_out, seconds)
                }
            }
            return result.settingsOutcome()
        }
        when (val step = pinFlowStep(state, typed, currentPin, pendingPin)) {
            PinFlowStep.CurrentPinCorrect -> {
                if (allowRemove) {
                    finish()
                    onRemovePin()
                } else {
                    goTo(PinFlowState.ENTER_PIN)
                }
            }

            PinFlowStep.Incorrect -> {
                Toast.makeText(context, context.getString(R.string.incorrect), Toast.LENGTH_SHORT).show()
                return SettingsPinOutcome.WRONG
            }

            PinFlowStep.NotFourPresses -> {
                dialog.message = context.getString(R.string.pin_must_be_four)
            }

            is PinFlowStep.ConfirmNewPin -> {
                pendingPin = step.pin
                goTo(PinFlowState.CONFIRM_PIN)
            }

            is PinFlowStep.SaveNewPin -> {
                // Suspends while it saves (the account first, contract 11.2), so the entry stays latched
                if (onAddPin(step.pin)) {
                    finish()
                } else {
                    pendingPin = ""
                    goTo(PinFlowState.ENTER_PIN, context.getString(R.string.pin_save_failed))
                }
            }

            PinFlowStep.Mismatch -> {
                pendingPin = ""
                goTo(PinFlowState.ENTER_PIN, context.getString(R.string.pin_mismatch))
            }
        }
        return SettingsPinOutcome.OTHER
    }
}
