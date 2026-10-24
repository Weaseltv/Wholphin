package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.ui.preferences.PinFlowState
import com.github.damontecres.wholphin.ui.preferences.PinFlowStep
import com.github.damontecres.wholphin.ui.preferences.pinFlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A PIN is exactly 4 directional presses (owner, 2026-10-22); older, longer PINs still work. */
class PinRulesTest {
    @Test
    fun `presses after the fourth are ignored`() {
        var input = ""
        "URDLUU".forEach { input = PinRules.append(input, it, PinRules.LENGTH) }
        assertEquals("URDL", input)
        assertTrue(PinRules.isComplete(input, PinRules.LENGTH))
        assertFalse(PinRules.isComplete("URD", PinRules.LENGTH))
    }

    @Test
    fun `a new pin must be exactly four presses`() {
        assertTrue(PinRules.isValidNewPin("URDL"))
        assertFalse(PinRules.isValidNewPin(""))
        assertFalse(PinRules.isValidNewPin("URD"))
        assertFalse(PinRules.isValidNewPin("URDLU"))
        // Arrows only: number keys (remote or keyboard) never make a new PIN
        assertFalse(PinRules.isValidNewPin("1234"))
        assertFalse(PinRules.isValidNewPin("UR1D"))
    }

    @Test
    fun `a saved pin is checked at its own length so nobody is locked out`() {
        assertEquals(4, PinRules.entryLength("1234"))
        assertEquals(6, PinRules.entryLength("UUDDLR"))
        assertEquals(4, PinRules.entryLength(null))
        assertEquals(4, PinRules.entryLength(""))
        // A 6-press PIN saved before the rule fits and can be typed in full
        var input = ""
        "UUDDLRR".forEach { input = PinRules.append(input, it, PinRules.entryLength("UUDDLR")) }
        assertEquals("UUDDLR", input)
    }

    @Test
    fun `setting a pin takes four presses then the same four again`() {
        assertEquals(PinFlowStep.NotFourPresses, pinFlowStep(PinFlowState.ENTER_PIN, "URD", null, ""))
        assertEquals(PinFlowStep.NotFourPresses, pinFlowStep(PinFlowState.ENTER_PIN, "1234", null, ""))
        assertEquals(PinFlowStep.ConfirmNewPin("URDL"), pinFlowStep(PinFlowState.ENTER_PIN, "URDL", null, ""))
        assertEquals(PinFlowStep.SaveNewPin("URDL"), pinFlowStep(PinFlowState.CONFIRM_PIN, "URDL", null, "URDL"))
        assertEquals(PinFlowStep.Mismatch, pinFlowStep(PinFlowState.CONFIRM_PIN, "URDD", null, "URDL"))
        assertEquals(PinFlowStep.Mismatch, pinFlowStep(PinFlowState.CONFIRM_PIN, "URD", null, "URDL"))
    }

    @Test
    fun `changing a longer old pin checks it in full, then needs a four-press one`() {
        val old = "UUDDLR"
        assertEquals(PinFlowStep.Incorrect, pinFlowStep(PinFlowState.ENTER_PIN_TO_REMOVE, "UUDD", old, ""))
        assertEquals(PinFlowStep.CurrentPinCorrect, pinFlowStep(PinFlowState.ENTER_PIN_TO_REMOVE, old, old, ""))
        // An older digit PIN is still checked as saved
        assertEquals(PinFlowStep.CurrentPinCorrect, pinFlowStep(PinFlowState.ENTER_PIN_TO_REMOVE, "1234", "1234", ""))
        assertEquals(PinFlowStep.NotFourPresses, pinFlowStep(PinFlowState.ENTER_PIN, old, old, ""))
    }
}
