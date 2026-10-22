package com.github.damontecres.wholphin.kids

/**
 * Profile and parent PINs (owner, 2026-10-22). Remotes have no number keys, so a PIN is a sequence of
 * directional presses, and a new PIN is exactly [LENGTH] of them.
 *
 * PINs are saved as plain text on the profile row, so the length of a saved PIN is known. A PIN saved
 * before this rule may be longer (the old minimum was 4, with no maximum): it keeps working, and its
 * entry shows as many boxes as it has presses. The next PIN set for that profile must be [LENGTH].
 */
object PinRules {
    const val LENGTH = 4

    /** How many presses to collect when checking [savedPin]: its own length, or [LENGTH] when unknown. */
    fun entryLength(savedPin: String?): Int = savedPin?.length?.takeIf { it > 0 } ?: LENGTH

    /** Whether [pin] may be saved as a new PIN. */
    fun isValidNewPin(pin: String): Boolean = pin.length == LENGTH

    /** [input] plus [press], unless it already holds [maxLength] presses: presses after the last are ignored. */
    fun append(
        input: String,
        press: Char,
        maxLength: Int,
    ): String = if (input.length >= maxLength) input else input + press

    /** Whether [input] is complete, so it is submitted without waiting for OK. */
    fun isComplete(
        input: String,
        maxLength: Int,
    ): Boolean = input.length >= maxLength
}
