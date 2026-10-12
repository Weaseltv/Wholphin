package com.github.damontecres.wholphin.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DiscoverSort
import com.github.damontecres.wholphin.data.filter.DiscoverSortAndDirection
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.flip
import com.github.damontecres.wholphin.ui.data.getStringRes
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/**
 * Button that displays current sort option and provides ability to choose another
 *
 * Long pressing will reverse the current sort as will selecting the current sort option from the list
 */
@Composable
fun SortByButton(
    sortOptions: List<ItemSortBy>,
    current: SortAndDirection,
    onSortChange: (SortAndDirection) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    contentColor: Color = Color.Unspecified,
    compact: Boolean = false,
) {
    val currentSort = current.sort
    val currentDirection = current.direction
    var sortByDropDown by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        if (collapsed) {
            ExpandableSortButton(
                current = current,
                onClick = { sortByDropDown = true },
                onLongClick = { onSortChange.invoke(current.flip()) },
                contentColor = contentColor,
                compact = compact,
            )
        } else {
            TextButton(
                onClick = { sortByDropDown = true },
                onLongClick = {
                    onSortChange.invoke(current.flip())
                },
            ) {
                Text(
                    text = current.toAnnotatedString(),
                )
            }
        }

        AppDropdownMenu(
            expanded = sortByDropDown,
            onDismissRequest = { sortByDropDown = false },
        ) {
            sortOptions
//                .sortedBy { it.name }
                .forEach { sortOption ->
                    TvDropdownMenuItem(
                        leadingIcon = {
                            if (sortOption == currentSort) {
                                if (currentDirection == SortOrder.ASCENDING) {
                                    Text(
                                        text = stringResource(R.string.fa_caret_up),
                                        fontFamily = FontAwesome,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.fa_caret_down),
                                        fontFamily = FontAwesome,
                                    )
                                }
                            }
                        },
                        text = {
                            Text(
                                text = stringResource(getStringRes(sortOption)),
                            )
                        },
                        onClick = {
                            sortByDropDown = false
                            val newDirection =
                                if (currentSort == sortOption) {
                                    currentDirection.flip()
                                } else {
                                    currentDirection
                                }
                            onSortChange.invoke(
                                SortAndDirection(
                                    sortOption,
                                    newDirection,
                                ),
                            )
                        },
                    )
                }
        }
    }
}

@Composable
fun SortAndDirection.toAnnotatedString() =
    buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FontAwesome)) {
            append(
                stringResource(
                    if (direction == SortOrder.ASCENDING) {
                        R.string.fa_caret_up
                    } else {
                        R.string.fa_caret_down
                    },
                ),
            )
        }
        append(" ")
        append(stringResource(getStringRes(sort)))
    }

// TODO code duplicated from SortByButton
@Composable
fun DiscoverSortByButton(
    sortOptions: List<DiscoverSort>,
    current: DiscoverSortAndDirection,
    onSortChange: (DiscoverSortAndDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSort = current.sort
    val currentDirection = current.direction
    var sortByDropDown by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(
            onClick = { sortByDropDown = true },
            onLongClick = {
                onSortChange.invoke(current.flip())
            },
        ) {
            Text(
                text = current.toAnnotatedString(),
            )
        }

        AppDropdownMenu(
            expanded = sortByDropDown,
            onDismissRequest = { sortByDropDown = false },
        ) {
            sortOptions
//                .sortedBy { it.name }
                .forEach { sortOption ->
                    TvDropdownMenuItem(
                        leadingIcon = {
                            if (sortOption == currentSort) {
                                if (currentDirection == SortOrder.ASCENDING) {
                                    Text(
                                        text = stringResource(R.string.fa_caret_up),
                                        fontFamily = FontAwesome,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.fa_caret_down),
                                        fontFamily = FontAwesome,
                                    )
                                }
                            }
                        },
                        text = {
                            Text(
                                text = stringResource(sortOption.stringRes),
                            )
                        },
                        onClick = {
                            sortByDropDown = false
                            val newDirection =
                                if (currentSort == sortOption) {
                                    currentDirection.flip()
                                } else {
                                    currentDirection
                                }
                            onSortChange.invoke(
                                DiscoverSortAndDirection(
                                    sortOption,
                                    newDirection,
                                ),
                            )
                        },
                    )
                }
        }
    }
}

@Composable
fun DiscoverSortAndDirection.toAnnotatedString() =
    buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FontAwesome)) {
            append(
                stringResource(
                    if (direction == SortOrder.ASCENDING) {
                        R.string.fa_caret_up
                    } else {
                        R.string.fa_caret_down
                    },
                ),
            )
        }
        append(" ")
        append(stringResource(sort.stringRes))
    }

/** A sort icon that shows the current sort while focused, like [ExpandableFilterButton]. */
@Composable
fun ExpandableSortButton(
    current: SortAndDirection,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    compact: Boolean = false,
    contentColor: Color = Color.Unspecified,
) {
    val isFocused = interactionSource.collectIsFocusedAsState().value
    val size = if (compact) COMPACT_BUTTON_SIZE else MinButtonSize
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier =
            modifier.requiredSizeIn(
                minWidth = size,
                minHeight = size,
                maxHeight = size,
            ),
        contentHeight = size,
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(min = if (isFocused) (size - 12.dp) else size),
        ) {
            Text(
                text =
                    stringResource(
                        if (current.direction ==
                            SortOrder.ASCENDING
                        ) {
                            R.string.fa_arrow_up_wide_short
                        } else {
                            R.string.fa_arrow_down_wide_short
                        },
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontSize = 16.sp,
                fontFamily = FontAwesome,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        AnimatedVisibility(isFocused) {
            Text(
                text = stringResource(getStringRes(current.sort)),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontSize = if (compact) COMPACT_FONT_SIZE else TextUnit.Unspecified,
                modifier = Modifier.padding(start = 4.dp, end = if (compact) 6.dp else 8.dp),
            )
        }
    }
}
