package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonRule
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus

@Composable
fun <T> ItemRow(
    title: String,
    items: List<T?>,
    onClickItem: (Int, T) -> Unit,
    onLongClickItem: (Int, T) -> Unit,
    cardContent: @Composable (
        index: Int,
        item: T?,
        modifier: Modifier,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    showViewMore: Boolean = false,
    viewMoreCardContent: @Composable (Modifier) -> Unit = {},
) {
    val state = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val focusRequester = remember { FocusRequester() }
    var position by rememberInt()

    val currentOnClickItem by rememberUpdatedState(onClickItem)
    val currentOnLongClickItem by rememberUpdatedState(onLongClickItem)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier.focusProperties {
                onEnter = {
                    focusRequester.tryRequestFocus()
                }
            },
    ) {
        ItemRowTitle(title, count = items.size.takeIf { isWeaselTv() })

        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(horizontalPadding),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = if (isWeaselTv()) 4.dp else 8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(firstFocus)
                    .focusRequester(focusRequester),
        ) {
            itemsIndexed(items) { index, item ->
                val cardModifier =
                    remember(index, position) {
                        if (index == position) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        }
                    }

                val onClick =
                    remember(index, item) {
                        {
                            position = index
                            if (item != null) currentOnClickItem(index, item)
                        }
                    }

                val onLongClick =
                    remember(index, item) {
                        {
                            position = index
                            if (item != null) currentOnLongClickItem(index, item)
                        }
                    }

                cardContent.invoke(
                    index,
                    item,
                    cardModifier,
                    onClick,
                    onLongClick,
                )
            }
            if (showViewMore) {
                item {
                    val cardModifier =
                        remember(items.size, position) {
                            if (position == items.size) {
                                Modifier.focusRequester(firstFocus)
                            } else {
                                Modifier
                            }
                        }
                    viewMoreCardContent.invoke(cardModifier)
                }
            }
        }
    }
}

/**
 * A row's title. Neon Board (`02-components-tv.md` § T4): Barlow Condensed 800 22sp
 * uppercase, an optional count on the right, then a 1dp rule in the row accent with
 * 14dp to the cards. Stock title on every other theme.
 */
@Composable
fun ItemRowTitle(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color = LocalNeonAccent.current,
) {
    if (!isWeaselTv()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = modifier.padding(start = 8.dp),
        )
        return
    }
    Column(
        modifier = modifier.padding(start = 8.dp, end = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title.uppercase(),
                style = NeonType.sectionTitle(),
                color = NeonBoard.Text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (count != null && count > 0) {
                Text(
                    text = count.toString(),
                    style = NeonType.count(),
                    color = NeonBoard.Mid,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        NeonRule(modifier = Modifier.padding(top = 4.dp), accent = accent)
    }
}
