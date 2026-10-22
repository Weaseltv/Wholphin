package com.github.damontecres.wholphin.ui.preferences

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog

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
 * its own saved length, so a longer PIN saved before that rule still works and is replaced by a 4-press one.
 *
 * @param allowRemove false on a kid-protected TV: entering the current PIN leads to choosing a new one
 * instead of removing it (plan 4.2)
 */
@Composable
fun SetPinFlow(
    currentPin: String?,
    onAddPin: (String) -> Unit,
    onRemovePin: () -> Unit,
    onDismissRequest: () -> Unit,
    allowRemove: Boolean = true,
) {
    val context = LocalContext.current
    var flowState by
        remember {
            mutableStateOf(if (currentPin.isNullOrBlank()) PinFlowState.ENTER_PIN else PinFlowState.ENTER_PIN_TO_REMOVE)
        }
    var pendingPin by remember { mutableStateOf("") }
    // Bumped after each PIN that keeps this step open, so the entry takes presses again
    var checks by remember { mutableIntStateOf(0) }

    fun toast(message: Int) = Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()

    AnimatedContent(flowState) { state ->
        PinEntryDialog(
            onDismissRequest = onDismissRequest,
            title =
                stringResource(
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
            checks = checks,
            onSubmit = { typed ->
                when (val step = pinFlowStep(state, typed, currentPin, pendingPin)) {
                    PinFlowStep.CurrentPinCorrect -> {
                        if (allowRemove) onRemovePin() else flowState = PinFlowState.ENTER_PIN
                    }

                    PinFlowStep.Incorrect -> {
                        toast(R.string.incorrect)
                        checks++
                    }

                    PinFlowStep.NotFourPresses -> {
                        toast(R.string.pin_must_be_four)
                        checks++
                    }

                    is PinFlowStep.ConfirmNewPin -> {
                        pendingPin = step.pin
                        flowState = PinFlowState.CONFIRM_PIN
                    }

                    is PinFlowStep.SaveNewPin -> {
                        onAddPin(step.pin)
                    }

                    PinFlowStep.Mismatch -> {
                        toast(R.string.incorrect)
                        pendingPin = ""
                        checks++
                        flowState = PinFlowState.ENTER_PIN
                    }
                }
            },
        )
    }
}
