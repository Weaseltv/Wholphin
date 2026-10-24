package com.github.damontecres.wholphin.ui.preferences

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.outcome

enum class PinFlowState {
    ENTER_PIN_TO_REMOVE,
    ENTER_PIN,
    CONFIRM_PIN,
}

enum class ProfileProtection {
    NONE,
    PIN,
    LOGIN,
}

/** What the PIN typed in [state] leads to; see [SetPinFlow]. */
sealed interface PinFlowStep {
    /** The current PIN was right: remove it, or choose a new one when removing isn't allowed. */
    data object CurrentPinCorrect : PinFlowStep

    data object Incorrect : PinFlowStep

    /** A new PIN must be exactly [PinRules.LENGTH] presses. */
    data object NotFourPresses : PinFlowStep

    data class ConfirmNewPin(
        val pin: String,
    ) : PinFlowStep

    data class SaveNewPin(
        val pin: String,
    ) : PinFlowStep

    /** The confirmation didn't match: start the new PIN again. */
    data object Mismatch : PinFlowStep
}

/**
 * The PIN-setting rules, without UI: [typed] in [state], with the profile's [currentPin] and the new PIN typed
 * first ([pendingPin]).
 */
fun pinFlowStep(
    state: PinFlowState,
    typed: String,
    currentPin: String?,
    pendingPin: String,
): PinFlowStep =
    when (state) {
        PinFlowState.ENTER_PIN_TO_REMOVE -> {
            if (typed == currentPin) PinFlowStep.CurrentPinCorrect else PinFlowStep.Incorrect
        }

        PinFlowState.ENTER_PIN -> {
            if (PinRules.isValidNewPin(typed)) PinFlowStep.ConfirmNewPin(typed) else PinFlowStep.NotFourPresses
        }

        PinFlowState.CONFIRM_PIN -> {
            if (PinRules.isValidNewPin(pendingPin) && typed == pendingPin) PinFlowStep.SaveNewPin(pendingPin) else PinFlowStep.Mismatch
        }
    }

/**
 * Set, change or remove a profile PIN. A new PIN is exactly [PinRules.LENGTH] presses; the current one is checked at
 * its own saved length, so a longer PIN saved before that rule still works and is replaced by a 4-press one. A mismatch
 * says so and asks for the new PIN again.
 *
 * @param checkCurrentPin checks the current PIN; pass the gate's check (backoff, account PIN re-check, contract 11.2).
 * Without it the PIN is compared with [currentPin] here.
 * @param onAddPin saves the new PIN and says whether it was saved; if not, the flow says so and asks for it again
 * @param allowRemove false on a kid-protected TV, and for "Change PIN": entering the current PIN leads to choosing a
 * new one instead of removing it (plan 4.2)
 * @param title the title of every step; "Enter PIN" / "Confirm PIN" when null
 */
@Composable
fun SetPinFlow(
    currentPin: String?,
    onAddPin: suspend (String) -> Boolean,
    onRemovePin: () -> Unit,
    onDismissRequest: () -> Unit,
    allowRemove: Boolean = true,
    checkCurrentPin: (suspend (String) -> PinCheckResult)? = null,
    title: String? = null,
) {
    val context = LocalContext.current
    var flowState by
        remember {
            mutableStateOf(if (currentPin.isNullOrBlank()) PinFlowState.ENTER_PIN else PinFlowState.ENTER_PIN_TO_REMOVE)
        }
    var pendingPin by remember { mutableStateOf("") }
    // A line under the boxes: wrong PIN, lockout, mismatch, a failed save
    var message by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(0L) }

    fun toast(message: Int) = Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()

    fun goTo(
        state: PinFlowState,
        line: String? = null,
    ) {
        message = line
        flowState = state
    }

    AnimatedContent(flowState) { state ->
        PinEntryDialog(
            onDismissRequest = onDismissRequest,
            title =
                title ?: stringResource(
                    if (state == PinFlowState.CONFIRM_PIN) R.string.confirm_pin else R.string.enter_pin,
                ),
            prompt =
                AnnotatedString(
                    stringResource(
                        when (state) {
                            PinFlowState.ENTER_PIN_TO_REMOVE -> R.string.pin_prompt_current
                            PinFlowState.ENTER_PIN -> R.string.pin_prompt_new
                            PinFlowState.CONFIRM_PIN -> R.string.pin_prompt_confirm
                        },
                    ),
                ),
            length = if (state == PinFlowState.ENTER_PIN_TO_REMOVE) PinRules.entryLength(currentPin) else PinRules.LENGTH,
            // A new PIN is arrows only; the current one is checked as saved
            allowDigits = state == PinFlowState.ENTER_PIN_TO_REMOVE,
            lockedUntilMillis = if (state == PinFlowState.ENTER_PIN_TO_REMOVE) lockedUntil else 0L,
            message = message.takeIf { flowState == state },
            onSubmit = { typed ->
                if (state == PinFlowState.ENTER_PIN_TO_REMOVE && checkCurrentPin != null) {
                    // Suspends for the gate's check, so the entry stays latched until it is done
                    val result = checkCurrentPin(typed)
                    when (result) {
                        PinCheckResult.Correct -> {
                            if (allowRemove) onRemovePin() else goTo(PinFlowState.ENTER_PIN)
                        }

                        is PinCheckResult.Wrong -> {
                            message = context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)
                        }

                        is PinCheckResult.LockedOut -> {
                            val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                            message = context.getString(R.string.kid_pin_locked_out, seconds)
                            lockedUntil = result.retryAtMillis
                        }
                    }
                    return@PinEntryDialog result.outcome()
                }
                when (val step = pinFlowStep(state, typed, currentPin, pendingPin)) {
                    PinFlowStep.CurrentPinCorrect -> {
                        if (allowRemove) onRemovePin() else goTo(PinFlowState.ENTER_PIN)
                    }

                    PinFlowStep.Incorrect -> {
                        toast(R.string.incorrect)
                        return@PinEntryDialog PinOutcome.WRONG
                    }

                    PinFlowStep.NotFourPresses -> {
                        message = context.getString(R.string.pin_must_be_four)
                    }

                    is PinFlowStep.ConfirmNewPin -> {
                        pendingPin = step.pin
                        goTo(PinFlowState.CONFIRM_PIN)
                    }

                    is PinFlowStep.SaveNewPin -> {
                        // Suspends while it saves (the account first, contract 11.2), so the entry stays latched
                        if (!onAddPin(step.pin)) {
                            pendingPin = ""
                            goTo(PinFlowState.ENTER_PIN, context.getString(R.string.pin_save_failed))
                        }
                    }

                    PinFlowStep.Mismatch -> {
                        pendingPin = ""
                        goTo(PinFlowState.ENTER_PIN, context.getString(R.string.pin_mismatch))
                    }
                }
                PinOutcome.OTHER
            },
        )
    }
}
