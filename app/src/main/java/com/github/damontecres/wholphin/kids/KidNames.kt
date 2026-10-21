package com.github.damontecres.wholphin.kids

import java.text.Normalizer

/**
 * The website's display-name rule (contract section 2), applied on the TV so the form can say
 * what's wrong before sending. The website's sanitized value stays authoritative.
 */
object KidNames {
    const val MAX_LENGTH = 20

    private val disallowed = Regex("[^\\p{L}\\p{N}_ \\-'.@+]")
    private val whitespace = Regex("\\s+")

    fun sanitize(input: String): String =
        Normalizer
            .normalize(input, Normalizer.Form.NFC)
            .replace(disallowed, "")
            .replace(whitespace, " ")
            .trim()

    /** The sanitized name when it is 1–20 characters, otherwise null. */
    fun validOrNull(input: String): String? = sanitize(input).takeIf { it.length in 1..MAX_LENGTH }
}
