package com.github.damontecres.wholphin.ui.preferences

import com.github.damontecres.wholphin.kids.PinRules

/*
 * The PIN-setting rules without UI, Compose-free (no-Compose rewrite, 2026-11-22). Moved unchanged from SetPinFlow.kt.
 */

/** The step of the set/change/remove PIN flow */
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
