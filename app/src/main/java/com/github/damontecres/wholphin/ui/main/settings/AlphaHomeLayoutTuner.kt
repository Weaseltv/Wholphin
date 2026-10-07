package com.github.damontecres.wholphin.ui.main.settings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.CuratedCollections
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.handleDPadKeyEvents
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.theme.LocalPosterCountAppearance
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.tryRequestFocus

/** Owner-operated Alpha tool; changes use the normal per-user Home settings. */
@Composable
fun AlphaHomeLayoutTuner(
    state: HomePageSettingsState,
    preferences: UserPreferences,
    onChange: (Int?, (HomeRowViewOptions) -> HomeRowViewOptions) -> Unit,
    onUpdateBackdrop: (BaseItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.rows.isEmpty()) return
    var rowIndex by remember {
        mutableIntStateOf(state.rows.indexOfFirst { CuratedCollections.isCuratedRow(it.config) }.coerceAtLeast(0))
    }
    rowIndex = rowIndex.coerceAtMost(state.rows.lastIndex)
    val row = state.rows[rowIndex]
    val curated = CuratedCollections.isCuratedRow(row.config)
    val options = row.config.viewOptions
    val appearance = options.cardAppearance
    val resolvedAppearance = appearance.copy(
        badgeTextSizeSp = appearance.badgeTextSizeSp ?: if (curated) 18 else 13,
        badgeHorizontalInsetDp = appearance.badgeHorizontalInsetDp ?: if (curated) 5 else 4,
        badgeVerticalInsetDp = appearance.badgeVerticalInsetDp ?: if (curated) 5 else 4,
        badgeHorizontalPaddingDp = appearance.badgeHorizontalPaddingDp ?: if (curated) 5 else 4,
        badgeVerticalPaddingDp = appearance.badgeVerticalPaddingDp ?: if (curated) 5 else 4,
    )
    var allRows by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(RowColumn(rowIndex, 0)) }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val originals = remember { state.rows.associate { it.id to it.config.viewOptions } }

    fun change(update: (HomeRowViewOptions) -> HomeRowViewOptions) {
        onChange(if (allRows) null else row.id, update)
    }

    BackHandler {
        if (inspect) inspect = false else onClose()
    }
    LaunchedEffect(row.id) {
        position = RowColumn(rowIndex, 0)
        listState.scrollToItem(rowIndex)
    }
    LaunchedEffect(inspect) {
        if (!inspect) firstFocus.tryRequestFocus()
    }

    Box(modifier = modifier.fillMaxSize()) {
        key(inspect) {
            CompositionLocalProvider(LocalPosterCountAppearance provides resolvedAppearance) {
            HomePageContent(
                loadingState = state.loading,
                homeRows = state.rowData,
                libraries = state.libraries,
                position = position,
                onFocusPosition = { position = it },
                onClickItem = { _, _ -> },
                onLongClickItem = { _, _ -> },
                onClickPlay = { _, _ -> },
                showClock = false,
                onUpdateBackdrop = onUpdateBackdrop,
                listState = listState,
                takeFocus = inspect,
                showEmptyRows = true,
                showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                showViewMore = false,
                modifier = Modifier.fillMaxSize(),
            )
            }
        }
        if (inspect) {
            Text(
                text = "Back: return to layout controls",
                color = NeonBoard.Text,
                modifier = Modifier.align(Alignment.TopEnd).background(NeonBoard.Scrim).padding(12.dp),
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .width(300.dp)
                        .fillMaxHeight()
                        .background(NeonBoard.Stage.copy(alpha = .96f))
                        .padding(12.dp)
                        .focusProperties { onExit = { cancelFocusChange() } }
                        .focusGroup(),
            ) {
                Text("Alpha · Card appearance", color = LocalNeonAccent.current)
                Text("Up/down: select · Left/right: adjust", color = NeonBoard.Mid)
                Text("Changes are live. Back saves and closes.", color = NeonBoard.Mid)
                Text(
                    "${row.title.getString()}\n" +
                        "Border ${appearance.borderWidthDp} dp · Opacity ${appearance.borderOpacityPercent}%\n" +
                        "Glow ${appearance.glowSpreadDp} dp · Strength ${appearance.glowOpacityPercent}%\n" +
                        "Focus ${appearance.focusScalePercent}% · Corners ${appearance.cornerRadiusDp} dp\n" +
                        "Badge ${resolvedAppearance.badgeTextSizeSp} sp · Text ${appearance.badgeTextOpacityPercent}% · Background ${appearance.badgeBackgroundOpacityPercent}%\n" +
                        "Badge inset ${resolvedAppearance.badgeHorizontalInsetDp}/${resolvedAppearance.badgeVerticalInsetDp} dp\n" +
                        "Badge padding ${resolvedAppearance.badgeHorizontalPaddingDp}/${resolvedAppearance.badgeVerticalPaddingDp} dp · Corners ${appearance.badgeCornerPercent}%",
                    color = NeonBoard.Text,
                    fontSize = 12.sp,
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    item {
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "Row ${rowIndex + 1}/${state.rows.size}: ${row.title.getString()}",
                            onClick = { rowIndex = (rowIndex + 1) % state.rows.size },
                            modifier =
                                Modifier.focusRequester(firstFocus).handleDPadKeyEvents(
                                    onLeft = { rowIndex = (rowIndex + state.rows.size - 1) % state.rows.size },
                                    onRight = { rowIndex = (rowIndex + 1) % state.rows.size },
                                ),
                        )
                    }
                    item {
                        HomeSettingsListItem(
                            selected = allRows,
                            headlineText = if (allRows) "Apply changes: all rows" else "Apply changes: this row",
                            onClick = { allRows = !allRows },
                        )
                        if (allRows) Text("Readouts show the selected row.", color = NeonBoard.Mid)
                        Text("Poster count settings apply app-wide.", color = NeonBoard.Mid)
                    }
                    items(LayoutControls, key = { it.label }) { control ->
                        val resolved = options.copy(cardAppearance = resolvedAppearance)
                        val value = control.get(resolved)
                        fun changeControl(next: Int) {
                            val update: (HomeRowViewOptions) -> HomeRowViewOptions = { control.set(it, next.coerceIn(control.range)) }
                            if (control.label.startsWith("Poster badge")) onChange(null, update) else change(update)
                        }
                        val displayedValue = if (control.valueDivisor == 1) value.toString() else (value.toFloat() / control.valueDivisor).toString()
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "${control.label}: $displayedValue ${control.unit}",
                            onClick = { changeControl(value + 1) },
                            modifier =
                                Modifier.handleDPadKeyEvents(
                                    triggerOnAction = KeyEvent.ACTION_DOWN,
                                    onLeft = { changeControl(value - 1) },
                                    onRight = { changeControl(value + 1) },
                                ),
                        )
                    }
                    item {
                        val colors = listOf("Row accent", "Volt", "Orange", "White", "Cyan", "Pink")
                        fun changeColor(delta: Int) {
                            val index = (appearance.accentIndex + delta + colors.size) % colors.size
                            change { it.copy(cardAppearance = it.cardAppearance.copy(accentIndex = index)) }
                        }
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "Focus border color: ${colors[appearance.accentIndex.coerceIn(colors.indices)]}",
                            onClick = { changeColor(1) },
                            modifier = Modifier.handleDPadKeyEvents(onLeft = { changeColor(-1) }, onRight = { changeColor(1) }),
                        )
                    }
                    item {
                        HomeSettingsListItem(selected = false, headlineText = "Hide controls / inspect Home", onClick = { inspect = true })
                    }
                    item {
                        HomeSettingsListItem(selected = false, headlineText = "Save & close", onClick = onClose)
                    }
                    item {
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "Discard this tuning session",
                            onClick = {
                                originals.forEach { (id, original) -> onChange(id) { original } }
                                onClose()
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class LayoutControl(
    val label: String,
    val range: IntRange,
    val get: (HomeRowViewOptions) -> Int,
    val set: (HomeRowViewOptions, Int) -> HomeRowViewOptions,
    val unit: String = "dp",
    val valueDivisor: Int = 1,
)

private val LayoutControls =
    listOf(
        LayoutControl("Focus border thickness", 0..8, { it.cardAppearance.borderWidthDp }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(borderWidthDp = v)) }),
        LayoutControl("Focus border opacity", 0..100, { it.cardAppearance.borderOpacityPercent }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(borderOpacityPercent = v)) }, "%"),
        LayoutControl("Focus glow spread", 0..48, { it.cardAppearance.glowSpreadDp }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(glowSpreadDp = v)) }),
        LayoutControl("Focus glow strength", 0..100, { it.cardAppearance.glowOpacityPercent }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(glowOpacityPercent = v)) }, "%"),
        LayoutControl("Focused card scale", 100..120, { it.cardAppearance.focusScalePercent }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(focusScalePercent = v)) }, "%"),
        LayoutControl("Card corner radius", 0..32, { it.cardAppearance.cornerRadiusDp }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(cornerRadiusDp = v)) }),
        LayoutControl("Poster badge text size", 8..40, { it.cardAppearance.badgeTextSizeSp ?: 13 }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeTextSizeSp = v)) }, "sp"),
        LayoutControl("Poster badge text opacity", 0..100, { it.cardAppearance.badgeTextOpacityPercent }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeTextOpacityPercent = v)) }, "%"),
        LayoutControl("Poster badge background opacity", 0..100, { it.cardAppearance.badgeBackgroundOpacityPercent }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeBackgroundOpacityPercent = v)) }, "%"),
        LayoutControl("Poster badge right inset", 0..64, { it.cardAppearance.badgeHorizontalInsetDp ?: 4 }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeHorizontalInsetDp = v)) }),
        LayoutControl("Poster badge top inset", 0..64, { it.cardAppearance.badgeVerticalInsetDp ?: 4 }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeVerticalInsetDp = v)) }),
        LayoutControl("Poster badge horizontal padding", 0..32, { it.cardAppearance.badgeHorizontalPaddingDp ?: 4 }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeHorizontalPaddingDp = v)) }),
        LayoutControl("Poster badge vertical padding", 0..32, { it.cardAppearance.badgeVerticalPaddingDp ?: 4 }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeVerticalPaddingDp = v)) }),
        LayoutControl("Poster badge corner rounding", 0..50, { it.cardAppearance.badgeCornerPercent }, { o, v -> o.copy(cardAppearance = o.cardAppearance.copy(badgeCornerPercent = v)) }, "%"),
    )
