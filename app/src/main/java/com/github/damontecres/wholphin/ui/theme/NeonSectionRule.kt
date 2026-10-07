package com.github.damontecres.wholphin.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.bleedHorizontal

/** Gutters between page content and its edges, excluding the navigation rail itself. */
data class NeonRuleInsets(
    val start: Dp = 0.dp,
    val end: Dp = 0.dp,
)

val LocalNeonRuleInsets = compositionLocalOf { NeonRuleInsets() }

/** Accounts for another padded container around section headers. */
@Composable
fun InsetNeonSectionRules(
    start: Dp,
    end: Dp = start,
    content: @Composable () -> Unit,
) {
    val parent = LocalNeonRuleInsets.current
    CompositionLocalProvider(
        LocalNeonRuleInsets provides NeonRuleInsets(parent.start + start, parent.end + end),
        content = content,
    )
}

/** Page section rules reach the content edges while labels and cards keep their gutters. */
@Composable
fun NeonSectionRule(
    modifier: Modifier = Modifier,
    accent: Color = LocalNeonAccent.current,
) {
    val insets = LocalNeonRuleInsets.current
    NeonRule(modifier = modifier.bleedHorizontal(insets.start, insets.end), accent = accent)
}
