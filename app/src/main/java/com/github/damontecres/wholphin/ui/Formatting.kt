package com.github.damontecres.wholphin.ui

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import java.util.Locale

fun AnnotatedString.Builder.dot() = append("  \u2022  ")

fun listToDotString(
    strings: List<String>,
    communityRating: Float?,
    criticRating: Float?,
): AnnotatedString =
    buildAnnotatedString {
        strings.forEachIndexed { index, string ->
            append(string)
            if (index != strings.lastIndex) dot()
        }
        communityRating?.let {
            dot()
            append(String.format(Locale.getDefault(), "%.1f", it))
            appendInlineContent(id = "star")
        }
        criticRating?.let {
            dot()
            append("${it.toInt()}%")
            if (it >= 60f) {
                appendInlineContent(id = "fresh")
            } else {
                appendInlineContent(id = "rotten")
            }
        }
    }
