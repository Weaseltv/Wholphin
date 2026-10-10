package com.github.damontecres.wholphin.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.keepFocusedItemVisible
import com.github.damontecres.wholphin.ui.preferences.SwitchColors
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonListItemBorder
import com.github.damontecres.wholphin.ui.theme.neonListItemColors
import com.github.damontecres.wholphin.ui.theme.neonListItemGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemShape

@Composable
fun SearchViewOptionsDialog(
    combinedResults: Boolean,
    onCombinedResultsChange: (Boolean) -> Unit,
    voiceSearchButtonVisible: Boolean,
    onVoiceSearchButtonVisibleChange: (Boolean) -> Unit,
    onClickFilterTypes: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    BasicDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 3.dp,
    ) {
        Box(modifier = Modifier.width(400.dp).padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (isWeaselTv()) stringResource(R.string.view_options).uppercase() else stringResource(R.string.view_options),
                    style = if (isWeaselTv()) NeonType.dialogTitle() else MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                ListItem(
                    shape = neonListItemShape(),
                    colors = neonListItemColors(),
                    border = neonListItemBorder(),
                    glow = neonListItemGlow(),
                    selected = false,
                    headlineContent = {
                        Text(stringResource(R.string.combined_search_results))
                    },
                    supportingContent = {
                        Text(
                            if (combinedResults) {
                                stringResource(R.string.combined_search_results_on)
                            } else {
                                stringResource(R.string.combined_search_results_off)
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = combinedResults,
                            onCheckedChange = onCombinedResultsChange,
                            colors = SwitchColors(),
                        )
                    },
                    onClick = { onCombinedResultsChange(!combinedResults) },
                    modifier = Modifier.fillMaxWidth().keepFocusedItemVisible(),
                )

                ListItem(
                    shape = neonListItemShape(),
                    colors = neonListItemColors(),
                    border = neonListItemBorder(),
                    glow = neonListItemGlow(),
                    selected = false,
                    headlineContent = {
                        Text(stringResource(R.string.show_voice_search_button))
                    },
                    supportingContent = {
                        Text(
                            if (voiceSearchButtonVisible) {
                                stringResource(R.string.visible_ui)
                            } else {
                                stringResource(R.string.hidden_ui)
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = voiceSearchButtonVisible,
                            onCheckedChange = onVoiceSearchButtonVisibleChange,
                            colors = SwitchColors(),
                        )
                    },
                    onClick = { onVoiceSearchButtonVisibleChange(!voiceSearchButtonVisible) },
                    modifier = Modifier.fillMaxWidth().keepFocusedItemVisible(),
                )

                ListItem(
                    shape = neonListItemShape(),
                    colors = neonListItemColors(),
                    border = neonListItemBorder(),
                    glow = neonListItemGlow(),
                    selected = false,
                    headlineContent = {
                        Text(stringResource(R.string.include_types))
                    },
                    onClick = onClickFilterTypes,
                    modifier = Modifier.fillMaxWidth().keepFocusedItemVisible(),
                )
            }
        }
    }
}
