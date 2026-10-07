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
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.CuratedCollections
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.handleDPadKeyEvents
import com.github.damontecres.wholphin.ui.main.HomePageContent
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
    val extra = options.extraVerticalPaddingDp ?: if (curated) CuratedCollections.EXTRA_VERTICAL_PADDING_DP else 0
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
                Text("Alpha · Live layout", color = NeonBoard.Volt)
                Text("Up/down: select · Left/right: adjust", color = NeonBoard.Mid)
                Text("Changes are live. Back saves and closes.", color = NeonBoard.Mid)
                Text(
                    "${row.title.getString()}\n" +
                        "Height ${options.heightDp} · Between ${options.spacing} dp\n" +
                        "Side ${options.edgePaddingDp ?: options.spacing} · End ${options.endPaddingDp ?: options.edgePaddingDp ?: options.spacing} dp\n" +
                        "Padding ${options.verticalPaddingDp} + extra $extra · Divider gap ${options.dividerGapDp} dp\n" +
                        "After row ${options.rowGapDp} · Title gap ${options.titleDividerGapDp} dp\n" +
                        "Title ${options.titleSizeSp} sp · Letter spacing ${options.titleLetterSpacingTenthsSp / 10f} sp\n" +
                        "Line ${options.dividerThicknessDp} · Glow ${options.dividerGlowDp} dp · Strength ${options.dividerGlowStrength}/100",
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
                    }
                    items(LayoutControls, key = { it.label }) { control ->
                        val resolved = options.copy(extraVerticalPaddingDp = extra, edgePaddingDp = options.edgePaddingDp ?: options.spacing)
                        val value = control.get(resolved)
                        val displayedValue = if (control.valueDivisor == 1) value.toString() else (value.toFloat() / control.valueDivisor).toString()
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "${control.label}: $displayedValue ${control.unit}",
                            onClick = { change { control.set(it, (value + 1).coerceIn(control.range)) } },
                            modifier =
                                Modifier.handleDPadKeyEvents(
                                    triggerOnAction = KeyEvent.ACTION_DOWN,
                                    onLeft = { change { control.set(it, (value - 1).coerceIn(control.range)) } },
                                    onRight = { change { control.set(it, (value + 1).coerceIn(control.range)) } },
                                ),
                        )
                    }
                    item {
                        Text(
                            "Divider → card: ${options.dividerGapDp} + ${options.verticalPaddingDp} + $extra = ${options.dividerGapDp + options.verticalPaddingDp + extra} dp (before focus enlargement)",
                            color = NeonBoard.Volt,
                        )
                    }
                    item {
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "Card shape: ${options.aspectRatio.label}",
                            onClick = {
                                change {
                                    val ratio = AspectRatio.entries[(options.aspectRatio.ordinal + 1) % AspectRatio.entries.size]
                                    it.copy(aspectRatio = ratio, episodeAspectRatio = ratio)
                                }
                            },
                        )
                    }
                    item {
                        HomeSettingsListItem(
                            selected = options.showTitles,
                            headlineText = "Card captions: ${if (options.showTitles) "on" else "off"}",
                            onClick = { change { it.copy(showTitles = !options.showTitles) } },
                        )
                    }
                    item {
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "Image fit: ${options.contentScale.name.lowercase()}",
                            onClick = {
                                val scale =
                                    when (options.contentScale) {
                                        PrefContentScale.FILL -> PrefContentScale.FIT
                                        PrefContentScale.FIT -> PrefContentScale.CROP
                                        else -> PrefContentScale.FILL
                                    }
                                change { it.copy(contentScale = scale, episodeContentScale = scale) }
                            },
                        )
                    }
                    item {
                        HomeSettingsListItem(
                            selected = false,
                            headlineText = "Set every row to 172 dp / 22 dp",
                            onClick = { onChange(null) { it.copy(heightDp = 172, spacing = 22, edgePaddingDp = null) } },
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
        LayoutControl("Card height", 64..320, { it.heightDp }, { o, v -> o.copy(heightDp = v) }),
        LayoutControl("Between cards", 0..80, { it.spacing }, { o, v -> o.copy(spacing = v) }),
        LayoutControl("Base vertical padding", 0..64, { it.verticalPaddingDp }, { o, v -> o.copy(verticalPaddingDp = v) }),
        LayoutControl("Extra vertical padding", 0..64, { it.extraVerticalPaddingDp ?: 0 }, { o, v -> o.copy(extraVerticalPaddingDp = v) }),
        LayoutControl("Divider → padding gap", 0..64, { it.dividerGapDp }, { o, v -> o.copy(dividerGapDp = v) }),
        LayoutControl("After row", 0..64, { it.rowGapDp }, { o, v -> o.copy(rowGapDp = v) }),
        LayoutControl("Row side padding", 0..80, { it.edgePaddingDp ?: it.spacing }, { o, v -> o.copy(edgePaddingDp = v, endPaddingDp = o.endPaddingDp ?: o.edgePaddingDp ?: o.spacing) }),
        LayoutControl("Row end padding", 0..160, { it.endPaddingDp ?: it.edgePaddingDp ?: it.spacing }, { o, v -> o.copy(endPaddingDp = v) }),
        LayoutControl("Title → divider gap", 0..32, { it.titleDividerGapDp }, { o, v -> o.copy(titleDividerGapDp = v) }),
        LayoutControl("Row title text size", 12..40, { it.titleSizeSp }, { o, v -> o.copy(titleSizeSp = v) }, "sp"),
        LayoutControl("Row title letter spacing", -20..100, { it.titleLetterSpacingTenthsSp }, { o, v -> o.copy(titleLetterSpacingTenthsSp = v) }, "sp", 10),
        LayoutControl("Divider thickness", 1..8, { it.dividerThicknessDp }, { o, v -> o.copy(dividerThicknessDp = v) }),
        LayoutControl("Divider glow spread", 0..48, { it.dividerGlowDp }, { o, v -> o.copy(dividerGlowDp = v) }),
        LayoutControl("Divider glow strength", 0..100, { it.dividerGlowStrength }, { o, v -> o.copy(dividerGlowStrength = v) }, "/ 100"),
    )

private val AspectRatio.label: String
    get() =
        when (this) {
            AspectRatio.TALL -> "Portrait 2:3"
            AspectRatio.WIDE -> "Landscape 16:9"
            AspectRatio.FOUR_THREE -> "Landscape 4:3"
            AspectRatio.SQUARE -> "Square 1:1"
        }
