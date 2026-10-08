package com.github.damontecres.wholphin.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.DialogListEdge
import com.github.damontecres.wholphin.ui.theme.LocalHomeMediaSettings
import com.github.damontecres.wholphin.ui.theme.NeonRule
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import androidx.tv.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ListItem
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.main.settings.TitleText
import com.github.damontecres.wholphin.ui.preferences.SwitchColors
import com.github.damontecres.wholphin.ui.titleStringRes
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun SearchTypeOptionsDialog(
    onDismissRequest: () -> Unit,
    searchableTypes: List<BaseItemKind>,
    excludedSearchableTypes: List<BaseItemKind>,
    discoverAvailable: Boolean,
    discoverEnabled: Boolean,
    onClick: (BaseItemKind) -> Unit,
    onClickDiscover: () -> Unit,
) {
    BasicDialog(
        onDismissRequest = onDismissRequest,
        elevation = 3.dp,
    ) {
        SearchTypeOptionsDialogContent(
            searchableTypes = searchableTypes,
            excludedSearchableTypes = excludedSearchableTypes,
            discoverAvailable = discoverAvailable,
            discoverEnabled = discoverEnabled,
            onClick = onClick,
            onClickDiscover = onClickDiscover,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
fun SearchTypeOptionsDialogContent(
    searchableTypes: List<BaseItemKind>,
    excludedSearchableTypes: List<BaseItemKind>,
    discoverAvailable: Boolean,
    discoverEnabled: Boolean,
    onClick: (BaseItemKind) -> Unit,
    onClickDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (isWeaselTv()) {
            Text(stringResource(R.string.include_types), style = NeonType.dialogTitle(), modifier = Modifier.padding(horizontal = 16.dp))
            val rule = LocalHomeMediaSettings.current.rows.firstOrNull()?.config?.viewOptions
                ?: ApprovedHomeLayout.apply(HomeRowViewOptions())
            NeonRule(
                modifier = Modifier.padding(top = 8.dp),
                thickness = rule.dividerThicknessDp.dp,
                glowHeight = rule.dividerGlowDp.dp,
                glowStrength = rule.dividerGlowStrength / 100f,
            )
        } else TitleText(stringResource(R.string.include_types))

        LazyColumn(contentPadding = PaddingValues(horizontal = if (isWeaselTv()) 32.dp else 0.dp, vertical = DialogListEdge)) {
            items(searchableTypes) { searchableType ->
                val checked = searchableType !in excludedSearchableTypes
                ListItem(
                    enabled = true,
                    selected = false,
                    onClick = { onClick.invoke(searchableType) },
                    headlineContent = {
                        Text(stringResource(searchableType.titleStringRes))
                    },
                    trailingContent = {
                        Switch(
                            checked = checked,
                            onCheckedChange = {},
                            colors = SwitchColors(),
                        )
                    },
                )
            }
            if (discoverAvailable) {
                item {
                    ListItem(
                        enabled = true,
                        selected = false,
                        onClick = onClickDiscover,
                        headlineContent = {
                            Text(stringResource(R.string.discover))
                        },
                        trailingContent = {
                            Switch(
                                checked = discoverEnabled,
                                onCheckedChange = {},
                                colors = SwitchColors(),
                            )
                        },
                    )
                }
            }
        }
    }
}


/** Primary search filters stay visible; optional Collections/Requests remain in View options. */
val PrimarySearchTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.PERSON)

@Composable
fun SearchTypeToggleRow(
    searchableTypes: List<BaseItemKind>,
    excludedSearchableTypes: List<BaseItemKind>,
    onClick: (BaseItemKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        PrimarySearchTypes.filter { it in searchableTypes }.forEach { type ->
            Button(
                onClick = { onClick(type) },
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(type.titleStringRes), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = type !in excludedSearchableTypes, onCheckedChange = null, colors = SwitchColors())
                }
            }
        }
    }
}
