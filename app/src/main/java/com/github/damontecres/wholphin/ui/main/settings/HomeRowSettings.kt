package com.github.damontecres.wholphin.ui.main.settings

import android.view.Gravity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.components.toAnnotatedString
import com.github.damontecres.wholphin.ui.data.BoxSetSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.getStringRes
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.preferences.ClickPreference
import com.github.damontecres.wholphin.ui.preferences.ComposablePreference
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup
import com.github.damontecres.wholphin.ui.preferences.PreferenceTitle
import com.github.damontecres.wholphin.ui.theme.neonListItemBorder
import com.github.damontecres.wholphin.ui.theme.neonListItemColors
import com.github.damontecres.wholphin.ui.theme.neonListItemGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemShape
import com.github.damontecres.wholphin.ui.tryRequestFocus
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

@Composable
fun HomeRowSettings(
    title: String,
    config: HomeRowConfig,
    preferenceOptions: List<PreferenceGroup<HomeRowViewOptions>>,
    viewOptions: HomeRowViewOptions,
    onViewOptionsChange: (HomeRowViewOptions) -> Unit,
    onConfigChange: (HomeRowConfig) -> Unit,
    onConfigAction: (HomeRowConfigAction) -> Unit,
    onApplyApplyAll: () -> Unit,
    modifier: Modifier = Modifier,
    defaultViewOptions: HomeRowViewOptions = HomeRowViewOptions(),
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
    Column(modifier = modifier) {
        TitleText(title)
        HomeSettingsLazyColumn {
            preferenceOptions.forEachIndexed { groupIndex, prefGroup ->
                if (preferenceOptions.size > 1) {
                    item {
                        SubTitleText(stringResource(prefGroup.title))
                    }
                }
                itemsIndexed(prefGroup.preferences) { index, pref ->
                    pref as AppPreference<HomeRowViewOptions, Any>
                    val interactionSource = remember { MutableInteractionSource() }
                    val value = pref.getter.invoke(viewOptions)
                    ComposablePreference(
                        preference = pref,
                        value = value,
                        onNavigate = {},
                        onValueChange = { newValue ->
                            onViewOptionsChange.invoke(pref.setter(viewOptions, newValue))
                        },
                        interactionSource = interactionSource,
                        onClickPreference = { pref ->
                            when (pref) {
                                Options.ViewOptionsReset -> {
                                    onViewOptionsChange.invoke(defaultViewOptions)
                                }

                                Options.ViewOptionsApplyAll -> {
                                    onApplyApplyAll.invoke()
                                }

                                Options.ViewOptionsUseThumb -> {
                                    onViewOptionsChange.invoke(
                                        viewOptions.copy(
                                            heightDp = Cards.HEIGHT_EPISODE,
                                            spacing = 20,
                                            imageType = ViewOptionImageType.THUMB,
                                            aspectRatio = AspectRatio.WIDE,
                                            contentScale = PrefContentScale.FIT,
                                            episodeImageType = ViewOptionImageType.THUMB,
                                            episodeAspectRatio = AspectRatio.WIDE,
                                            episodeContentScale = PrefContentScale.FIT,
                                        ),
                                    )
                                }
                            }
                        },
                        modifier =
                            Modifier
                                .ifElse(
                                    groupIndex == 0 && index == 0,
                                    Modifier.focusRequester(firstFocus),
                                ),
                    )
                }
            }
            addAdditionalConfig(
                config = config,
                onConfigChange = onConfigChange,
                onConfigAction = onConfigAction,
            )
        }
    }
}

private fun LazyListScope.addAdditionalConfig(
    config: HomeRowConfig,
    onConfigChange: (HomeRowConfig) -> Unit,
    onConfigAction: (HomeRowConfigAction) -> Unit,
) {
    when (config) {
        is HomeRowConfig.ContinueWatchingCombined -> {
            item {
                ClickPreference(
                    title = stringResource(R.string.split_into_separate_rows),
                    onClick = { onConfigAction.invoke(HomeRowConfigAction.Split) },
                )
            }
        }

        is HomeRowConfig.ContinueWatching,
        is HomeRowConfig.NextUp,
        -> {
            item {
                ClickPreference(
                    title = stringResource(R.string.combine_continue_next),
                    onClick = { onConfigAction.invoke(HomeRowConfigAction.Combine) },
                )
            }
        }

        is HomeRowConfig.ByParent -> {
            item {
                val sortAndDirection =
                    remember(config) {
                        config.sort ?: SortAndDirection(
                            ItemSortBy.DEFAULT,
                            SortOrder.ASCENDING,
                        )
                    }
                var showDialog by remember { mutableStateOf(false) }
                ListItem(
                    shape = neonListItemShape(),
                    colors = neonListItemColors(),
                    border = neonListItemBorder(),
                    glow = neonListItemGlow(),
                    selected = false,
                    onClick = { showDialog = true },
                    headlineContent = {
                        PreferenceTitle(stringResource(R.string.sort))
                    },
                    supportingContent = {
                        Text(
                            text = sortAndDirection.toAnnotatedString(),
                        )
                    },
                )
                if (showDialog) {
                    BasicDialog(
                        onDismissRequest = { showDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                        elevation = 5.dp,
                    ) {
                        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
                        dialogWindowProvider?.window?.let { window ->
                            window.setGravity(Gravity.START)
                        }
                        ByParentSortDialogContent(
                            current = sortAndDirection,
                            onClick = {
                                val newSort =
                                    if (config.sort?.sort == it) {
                                        config.sort.flip()
                                    } else if (it == ItemSortBy.DEFAULT) {
                                        null
                                    } else {
                                        SortAndDirection(it, SortOrder.ASCENDING)
                                    }
                                onConfigChange.invoke(config.copy(sort = newSort))
                                showDialog = false
                            },
                            modifier = Modifier.width(320.dp),
                        )
                    }
                }
            }
        }

        else -> {}
    }
}

@Composable
fun ByParentSortDialogContent(
    current: SortAndDirection,
    onClick: (ItemSortBy) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        modifier = modifier,
    ) {
        items(BoxSetSortOptions) { sort ->
            ListItem(
                shape = neonListItemShape(),
                colors = neonListItemColors(),
                border = neonListItemBorder(),
                glow = neonListItemGlow(),
                selected = sort == current.sort,
                onClick = { onClick.invoke(sort) },
                leadingContent = {
                    Text(
                        fontFamily = FontAwesome,
                        text =
                            if (sort == current.sort) {
                                stringResource(
                                    if (current.direction == SortOrder.ASCENDING) {
                                        R.string.fa_caret_up
                                    } else {
                                        R.string.fa_caret_down
                                    },
                                )
                            } else {
                                ""
                            },
                    )
                },
                headlineContent = {
                    Text(
                        text = stringResource(getStringRes(sort)),
                    )
                },
            )
        }
    }
}
