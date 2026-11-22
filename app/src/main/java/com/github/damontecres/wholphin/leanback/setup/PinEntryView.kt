package com.github.damontecres.wholphin.leanback.setup

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.forgotPinUrl
import com.github.damontecres.wholphin.ui.setup.outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

/** The PIN character a remote key stands for, or null when it isn't part of a PIN (OK never is); `pinPress` */
internal fun pinPressFor(keyCode: Int): Char? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> 'U'
        KeyEvent.KEYCODE_DPAD_RIGHT -> 'R'
        KeyEvent.KEYCODE_DPAD_DOWN -> 'D'
        KeyEvent.KEYCODE_DPAD_LEFT -> 'L'
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
        else -> null
    }

/** "Enter **[name]'s** PIN to continue" (`pinPromptFor`) */
internal fun Context.pinPromptFor(name: String?): CharSequence? =
    name?.takeIf { it.isNotBlank() }?.let { emphasise(getString(R.string.pin_prompt_name, it), "$it's") }

/** What a [PinEntryView] asks for; see the parameters of the old `PinEntry` */
internal data class PinEntryConfig(
    val onSubmit: suspend (String) -> PinOutcome,
    val length: Int = PinRules.LENGTH,
    val allowDigits: Boolean = true,
    val lockedUntilMillis: Long = 0L,
    /** null: no title; the default is "Enter PIN" */
    val title: String? = DEFAULT_TITLE,
    val prompt: CharSequence? = null,
    val message: String? = null,
    val onClickServerAuth: (() -> Unit)? = null,
    val serverAuthRemovesPin: Boolean = true,
) {
    companion object {
        /** Marks the default title, resolved with a context */
        const val DEFAULT_TITLE = "\u0000enter_pin"
    }
}

/**
 * The PIN prompt (owner mockup `4-pin.png`; `PinEntry`): [PinEntryConfig.length] boxes, the D-pad as a hint, and the OK /
 * BACK keys.
 *
 * Presses after the length-th are ignored; the length-th submits the PIN on its own, and OK submits what is typed so far.
 * Only a press that started here counts, so the OK that opened the prompt never submits it. With nothing typed, OK runs
 * `onClickServerAuth` when set ("Login via server"); Back dismisses the dialog around it.
 *
 * The entry owns its latch: while `onSubmit` runs every PIN key and OK is ignored, so nothing typed meanwhile can start a
 * second guess or cancel the pending one. The latch is released however `onSubmit` ends and the boxes clear; if it
 * throws, a retryable error shows. Keys are ignored until the lockout ends too. The latch and the lockout are read when a
 * key arrives.
 *
 * The boxes and the key-hint footer always keep their room: the title and prompt are limited to two lines each, and in
 * a short panel the message is shortened and the D-pad hint dropped instead.
 *
 * Forgot PIN (contract 11.2): after more than [PinRules.FORGOT_PIN_AFTER_WRONG] wrong entries the D-pad hint gives way to
 * "Forgot your PIN?" with the website's reset page and a QR code for it.
 */
@SuppressLint("ViewConstructor")
internal class PinEntryView(
    context: Context,
    private val config: PinEntryConfig,
) : LinearLayout(context) {
    /** The line under the boxes (wrong PIN, locked out) */
    var message: String? = config.message
        set(value) {
            field = value
            render()
        }

    var lockedUntilMillis: Long = config.lockedUntilMillis
        set(value) {
            val changed = field != value
            field = value
            if (changed) startLockout()
        }

    var onClickServerAuth: (() -> Unit)? = config.onClickServerAuth
        set(value) {
            field = value
            render()
        }

    private var input = ""
    private var pressed: Int? = null
    private var busy = false
    private var failed = false
    private var wrongEntries = 0
    private var lockedOut = config.lockedUntilMillis > System.currentTimeMillis()
    private var scope: CoroutineScope? = null
    private var lockoutJob: Job? = null

    private val boxes = PinBoxesView(context, config.length)
    private val messageView = context.kidErrorText("", maxLines = 2)
    private val messageGap = context.gap(12f)
    private val forgotUrl = forgotPinUrl()
    private val forgot: View? = forgotUrl?.let { context.forgotPinHint(it) }
    private val forgotGap = context.gap(16f)
    private val dpad = SqueezableColumn(context)
    private val middle = MiddleColumn(context)
    private val okHint = TextView(context)
    private val okHintRow: LinearLayout
    private val removeNote: TextView =
        context.setupText(context.getString(R.string.will_remove_pin), KidPalette.FAINT) { kidBody(12f) }
    private val removeGap = context.gap(6f)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        val title = if (config.title == PinEntryConfig.DEFAULT_TITLE) context.getString(R.string.enter_pin) else config.title
        if (title != null) addView(context.kidTitle(title, maxLines = 2), wrap(Gravity.CENTER_HORIZONTAL))
        if (config.prompt != null) {
            addView(context.gap(10f))
            addView(context.kidSubtitle(config.prompt, KidPalette.TEXT, 19f, 2), wrap(Gravity.CENTER_HORIZONTAL))
        }
        addView(context.gap(22f))
        addView(boxes, wrap(Gravity.CENTER_HORIZONTAL))
        middle.addView(messageGap)
        middle.addView(messageView, wrap(Gravity.CENTER_HORIZONTAL))
        if (forgot != null) {
            middle.addView(forgotGap)
            middle.addView(forgot, wrap(Gravity.CENTER_HORIZONTAL))
        }
        dpad.addView(context.gap(18f))
        dpad.addView(context.dpadHint(), wrap(Gravity.CENTER_HORIZONTAL))
        middle.addView(dpad, wrap(Gravity.CENTER_HORIZONTAL))
        addView(middle, wrap(Gravity.CENTER_HORIZONTAL))
        addView(context.gap(18f))
        addView(context.kidDivider())
        addView(context.gap(16f))
        // The OK hint's label changes, so it is built here instead of with keyHint()
        okHintRow = context.keyHint(context.getString(R.string.kid_key_ok), "")
        val footer =
            horizontal(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(okHintRow)
                addView(context.hGap(28f))
                addView(context.keyHint(context.getString(R.string.kid_key_back), context.getString(R.string.cancel)))
            }
        addView(footer, wrap(Gravity.CENTER_HORIZONTAL))
        addView(removeGap)
        addView(removeNote, wrap(Gravity.CENTER_HORIZONTAL))
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startLockout()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private fun startLockout() {
        lockoutJob?.cancel()
        val until = lockedUntilMillis
        val s = scope ?: return
        lockoutJob =
            s.launch {
                val wait = until - System.currentTimeMillis()
                if (wait > 0) {
                    lockedOut = true
                    delay(wait)
                }
                lockedOut = false
            }
    }

    // Read at key time: a 4th press followed straight away by OK finds the latch already set
    private fun accepting(): Boolean = !busy && !lockedOut && System.currentTimeMillis() >= lockedUntilMillis

    private fun submit(pin: String) {
        if (!accepting()) return
        val s = scope ?: return
        busy = true
        failed = false
        input = pin
        render()
        s.launch {
            try {
                if (config.onSubmit(pin) == PinOutcome.WRONG) wrongEntries++
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "PIN check failed")
                failed = true
            } finally {
                input = ""
                busy = false
                render()
            }
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (pinPressFor(keyCode) == null && !isEnterKeyCode(keyCode)) return super.onKeyDown(keyCode, event)
        if (pressed == null) pressed = keyCode
        return true
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val isPinKey = pinPressFor(keyCode) != null
        if (!isPinKey && !isEnterKeyCode(keyCode)) return super.onKeyUp(keyCode, event)
        if (pressed == keyCode) {
            pressed = null
            // A number key while choosing a new PIN isn't a press at all
            val press = pinPressFor(keyCode)?.takeIf { config.allowDigits || it in PinRules.ARROWS }
            when {
                !accepting() -> {}

                isPinKey -> {
                    if (press != null) {
                        val next = PinRules.append(input, press, config.length)
                        if (PinRules.isComplete(next, config.length) && next != input) {
                            submit(next)
                        } else {
                            input = next
                            render()
                        }
                    }
                }

                input.isNotEmpty() -> {
                    submit(input)
                }

                else -> {
                    onClickServerAuth?.invoke()
                }
            }
        }
        return true
    }

    private fun render() {
        boxes.filled = input.length
        val shown = if (failed) context.getString(R.string.kids_error_generic) else message
        messageView.text = shown
        messageView.visibility = if (shown != null) View.VISIBLE else View.GONE
        messageGap.visibility = messageView.visibility
        val showForgot = forgot != null && PinRules.offersForgotPin(wrongEntries)
        forgot?.visibility = if (showForgot) View.VISIBLE else View.GONE
        forgotGap.visibility = if (showForgot) View.VISIBLE else View.GONE
        dpad.visibility = if (showForgot) View.GONE else View.VISIBLE
        val serverAuthHint = onClickServerAuth != null && input.isEmpty() && !busy
        (okHintRow.getChildAt(2) as TextView).text =
            context.getString(if (serverAuthHint) R.string.use_server_credentials else R.string.confirm)
        val note = serverAuthHint && config.serverAuthRemovesPin
        removeNote.visibility = if (note) View.VISIBLE else View.GONE
        removeGap.visibility = removeNote.visibility
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        // Only what is left after the header, boxes and footer goes to the message and hint
        middle.limit = Int.MAX_VALUE
        super.onMeasure(widthMeasureSpec, MeasureSpec.UNSPECIFIED)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val maxH = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        if (measuredHeight > maxH) {
            middle.limit = max(0, middle.measuredHeight - (measuredHeight - maxH))
            super.onMeasure(widthMeasureSpec, MeasureSpec.UNSPECIFIED)
        }
        setMeasuredDimension(measuredWidth, min(measuredHeight, maxH))
    }

    /** The message and hint column: at most [limit] tall, dropping the D-pad hint first, then clipping */
    private inner class MiddleColumn(
        context: Context,
    ) : LinearLayout(context) {
        var limit = Int.MAX_VALUE

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            dpad.squeezed = false
            super.onMeasure(widthMeasureSpec, MeasureSpec.UNSPECIFIED)
            if (measuredHeight > limit && dpad.visibility == View.VISIBLE) {
                dpad.squeezed = true
                super.onMeasure(widthMeasureSpec, MeasureSpec.UNSPECIFIED)
            }
            setMeasuredDimension(measuredWidth, min(measuredHeight, limit))
        }
    }

    /** Lays the D-pad hint out only if it fits; otherwise it takes no space */
    private class SqueezableColumn(
        context: Context,
    ) : LinearLayout(context) {
        var squeezed = false

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (squeezed) setMeasuredDimension(measuredWidth, 0)
        }
    }
}

/**
 * [PinEntryView] in its own kid panel dialog, 590dp wide and not scrolling (the entry takes every D-pad key, so its
 * footer must stay on screen instead); Back calls [onDismissRequest] (`PinEntryDialog`).
 */
internal class PinEntryDialog(
    context: Context,
    onDismissRequest: () -> Unit,
    config: PinEntryConfig? = null,
) : KidPanelDialog(context, 590f, scrollable = false, onDismissRequest = onDismissRequest) {
    var entry: PinEntryView? = null
        private set

    init {
        config?.let { setEntry(it) }
    }

    /** A fresh entry (a new prompt or step): its own presses, latch and wrong-entry count */
    fun setEntry(config: PinEntryConfig) {
        val content = reset()
        val view = PinEntryView(context, config)
        content.addView(view, matchWidth())
        entry = view
        initialFocus = view
        if (isShowing) view.requestFocus()
    }
}

/**
 * Set, change or remove a profile PIN (`SetPinFlow` in `ui/preferences`, ported for the setup screens). A new PIN is
 * exactly [PinRules.LENGTH] presses; the current one is checked at its own saved length. A mismatch says so and asks for
 * the new PIN again.
 *
 * @param checkCurrentPin checks the current PIN with the gate (backoff, account PIN re-check); without it the PIN is
 * compared with [currentPin] here
 * @param onAddPin saves the new PIN and says whether it was saved; if not, the flow says so and asks for it again
 * @param allowRemove false for "Change PIN": the current PIN leads to choosing a new one instead of removing it
 * @param title the title of every step; "Enter PIN" / "Confirm PIN" when null
 */
internal class SetPinFlow(
    private val context: Context,
    private val currentPin: String?,
    private val onAddPin: suspend (String) -> Boolean,
    private val onRemovePin: () -> Unit,
    onDismissRequest: () -> Unit,
    private val allowRemove: Boolean = true,
    private val checkCurrentPin: (suspend (String) -> PinCheckResult)? = null,
    private val title: String? = null,
) {
    private enum class State { ENTER_PIN_TO_REMOVE, ENTER_PIN, CONFIRM_PIN }

    private var state = if (currentPin.isNullOrBlank()) State.ENTER_PIN else State.ENTER_PIN_TO_REMOVE
    private var pendingPin = ""
    private var message: String? = null
    private var lockedUntil = 0L
    val dialog = PinEntryDialog(context, onDismissRequest)

    fun show() {
        dialog.setEntry(config(state))
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    private fun config(s: State): PinEntryConfig =
        PinEntryConfig(
            title = title ?: context.getString(if (s == State.CONFIRM_PIN) R.string.confirm_pin else R.string.enter_pin),
            prompt =
                context.getString(
                    when (s) {
                        State.ENTER_PIN_TO_REMOVE -> R.string.pin_prompt_current
                        State.ENTER_PIN -> R.string.pin_prompt_new
                        State.CONFIRM_PIN -> R.string.pin_prompt_confirm
                    },
                ),
            length = if (s == State.ENTER_PIN_TO_REMOVE) PinRules.entryLength(currentPin) else PinRules.LENGTH,
            // A new PIN is arrows only; the current one is checked as saved
            allowDigits = s == State.ENTER_PIN_TO_REMOVE,
            lockedUntilMillis = if (s == State.ENTER_PIN_TO_REMOVE) lockedUntil else 0L,
            message = message,
            onSubmit = { typed -> submit(s, typed) },
        )

    private fun setMessage(line: String?) {
        message = line
        dialog.entry?.message = line
    }

    private fun goTo(
        next: State,
        line: String? = null,
    ) {
        message = line
        state = next
        dialog.setEntry(config(next))
    }

    private suspend fun submit(
        s: State,
        typed: String,
    ): PinOutcome {
        val check = checkCurrentPin
        if (s == State.ENTER_PIN_TO_REMOVE && check != null) {
            // Suspends for the gate's check, so the entry stays latched until it is done
            val result = check(typed)
            when (result) {
                PinCheckResult.Correct -> {
                    if (allowRemove) onRemovePin() else goTo(State.ENTER_PIN)
                }

                is PinCheckResult.Wrong -> {
                    setMessage(context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout))
                }

                is PinCheckResult.LockedOut -> {
                    val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                    setMessage(context.getString(R.string.kid_pin_locked_out, seconds))
                    lockedUntil = result.retryAtMillis
                    dialog.entry?.lockedUntilMillis = result.retryAtMillis
                }
            }
            return result.outcome()
        }
        when (s) {
            State.ENTER_PIN_TO_REMOVE -> {
                if (typed == currentPin) {
                    if (allowRemove) onRemovePin() else goTo(State.ENTER_PIN)
                } else {
                    Toast.makeText(context, context.getString(R.string.incorrect), Toast.LENGTH_SHORT).show()
                    return PinOutcome.WRONG
                }
            }

            State.ENTER_PIN -> {
                if (PinRules.isValidNewPin(typed)) {
                    pendingPin = typed
                    goTo(State.CONFIRM_PIN)
                } else {
                    setMessage(context.getString(R.string.pin_must_be_four))
                }
            }

            State.CONFIRM_PIN -> {
                if (PinRules.isValidNewPin(pendingPin) && typed == pendingPin) {
                    // Suspends while it saves (the account first, contract 11.2), so the entry stays latched
                    if (!onAddPin(pendingPin)) {
                        pendingPin = ""
                        goTo(State.ENTER_PIN, context.getString(R.string.pin_save_failed))
                    }
                } else {
                    pendingPin = ""
                    goTo(State.ENTER_PIN, context.getString(R.string.pin_mismatch))
                }
            }
        }
        return PinOutcome.OTHER
    }
}
