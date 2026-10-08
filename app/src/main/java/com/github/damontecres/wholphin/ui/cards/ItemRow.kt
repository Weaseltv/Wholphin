package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.bleedHorizontal
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.LocalNeonRuleInsets
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonSectionRule
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
    titleAccent: Color = LocalNeonAccent.current,
    viewMoreCardContent: @Composable (Modifier) -> Unit = {},
    cardContentPadding: PaddingValues? = null,
    dividerGap: Dp = 8.dp,
    titleDividerGap: Dp = 4.dp,
    onTitleHeightChanged: (Int) -> Unit = {},
    dividerThickness: Dp = 1.dp,
    dividerGlow: Dp = 12.dp,
    dividerGlowStrength: Float = .35f,
    titleSize: TextUnit = 22.sp,
    titleLetterSpacing: TextUnit = .08.em,
    countSize: TextUnit = 12.sp,
    countOpacity: Float = 1f,
    countEndPadding: Dp = 8.dp,
    titleStartPadding: Dp? = null,
) {
    val state = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val focusRequester = remember { FocusRequester() }
    var position by rememberInt()

    val currentOnClickItem by rememberUpdatedState(onClickItem)
    val currentOnLongClickItem by rememberUpdatedState(onLongClickItem)
    val neon = isWeaselTv()
    val startInset = if (neon) LocalNeonRuleInsets.current.start else 0.dp
    val layoutDirection = LocalLayoutDirection.current
    val cardPadding =
        cardContentPadding
            ?: PaddingValues(horizontal = horizontalPadding, vertical = if (neon) 4.dp else 8.dp)

    Column(
        verticalArrangement = Arrangement.spacedBy(dividerGap),
        modifier =
            modifier.focusProperties {
                onEnter = {
                    focusRequester.tryRequestFocus()
                }
            },
    ) {
        ItemRowTitle(
            title,
            count = items.size.takeIf { isWeaselTv() },
            accent = titleAccent,
            dividerGap = titleDividerGap,
            modifier = Modifier.onSizeChanged { onTitleHeightChanged(it.height) },
            dividerThickness = dividerThickness,
            dividerGlow = dividerGlow,
            dividerGlowStrength = dividerGlowStrength,
            titleSize = titleSize,
            titleLetterSpacing = titleLetterSpacing,
            countSize = countSize,
            countOpacity = countOpacity,
            countEndPadding = countEndPadding,
            titleStartPadding = titleStartPadding ?: if (neon) cardPadding.calculateStartPadding(layoutDirection) else 8.dp,
        )

        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(horizontalPadding),
            contentPadding =
                PaddingValues(
                    start = cardPadding.calculateStartPadding(layoutDirection) + startInset,
                    end = cardPadding.calculateEndPadding(layoutDirection),
                    top = cardPadding.calculateTopPadding(),
                    bottom = cardPadding.calculateBottomPadding(),
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Move the clipping edge into the gutter without moving the cards.
                    .bleedHorizontal(start = startInset, end = 0.dp)
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
 * A row's title. Neon Board (`02-components-tv.md` § T4): Orbitron 800 22sp
 * uppercase, an optional count on the right, then a 1dp rule in the row accent with
 * 14dp to the cards. Stock title on every other theme.
 */
@Composable
fun ItemRowTitle(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color = LocalNeonAccent.current,
    dividerGap: Dp = 4.dp,
    dividerThickness: Dp = 1.dp,
    dividerGlow: Dp = 12.dp,
    dividerGlowStrength: Float = .35f,
    titleSize: TextUnit = 22.sp,
    titleLetterSpacing: TextUnit = .08.em,
    countSize: TextUnit = 12.sp,
    countOpacity: Float = 1f,
    countEndPadding: Dp = 8.dp,
    titleStartPadding: Dp = 8.dp,
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
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(start = titleStartPadding.coerceAtLeast(0.dp), end = countEndPadding),
        ) {
            Text(
                text = title.uppercase(),
                style = NeonType.sectionTitle().copy(fontSize = titleSize, lineHeight = titleSize * 1.2f, letterSpacing = titleLetterSpacing),
                color = NeonBoard.Text,
                maxLines = 1,
                modifier = Modifier.weight(1f).offset(x = titleStartPadding.coerceAtMost(0.dp)),
            )
            if (count != null && count > 0) {
                Text(
                    text = count.toString(),
                    style = NeonType.count().copy(fontSize = countSize, lineHeight = countSize * 1.2f),
                    color = NeonBoard.Mid.copy(alpha = countOpacity.coerceIn(0f, 1f)),
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        NeonSectionRule(
            modifier = Modifier.padding(top = dividerGap),
            accent = accent,
            thickness = dividerThickness,
            glowHeight = dividerGlow,
            glowStrength = dividerGlowStrength,
        )
    }
}
