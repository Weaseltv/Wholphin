package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.ui.bleedHorizontal
import com.github.damontecres.wholphin.ui.components.FocusSafeLazyRow
import com.github.damontecres.wholphin.ui.components.keepTitledRowVisible
import com.github.damontecres.wholphin.ui.components.reportDetailRowHeight
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.LocalNeonRuleInsets
import com.github.damontecres.wholphin.ui.theme.NeonSectionRule
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonGlowAccent
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
    horizontalPadding: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.CARD_SPACING_DP.dp else 16.dp,
    showViewMore: Boolean = false,
    titleAccent: Color = LocalNeonAccent.current,
    viewMoreCardContent: @Composable (Modifier) -> Unit = {},
    cardContentPadding: PaddingValues? = null,
    dividerGap: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.DIVIDER_GAP_DP.dp else 8.dp,
    titleDividerGap: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.TITLE_DIVIDER_GAP_DP.dp else 4.dp,
    onTitleHeightChanged: (Int) -> Unit = {},
    dividerThickness: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.DIVIDER_THICKNESS_DP.dp else 1.dp,
    dividerGlow: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.DIVIDER_GLOW_DP.dp else 12.dp,
    dividerGlowStrength: Float = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.DIVIDER_GLOW_STRENGTH / 100f else .35f,
    titleSize: TextUnit = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.TITLE_SIZE_SP.sp else 22.sp,
    titleLetterSpacing: TextUnit =
        if (BuildConfig.FLAVOR ==
            "weaselfin"
        ) {
            (ApprovedHomeLayout.TITLE_TRACKING_TENTHS_SP / 10f).sp
        } else {
            .06.em
        },
    countSize: TextUnit = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.COUNT_SIZE_SP.sp else 12.sp,
    countOpacity: Float = 1f,
    countEndPadding: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.COUNT_END_PADDING_DP.dp else 8.dp,
    titleStartPadding: Dp? = null,
    titleKicker: String? = null,
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
            ?: PaddingValues(
                start = if (neon) ApprovedHomeLayout.EDGE_PADDING_DP.dp else horizontalPadding,
                end = if (neon) ApprovedHomeLayout.END_PADDING_DP.dp else horizontalPadding,
                top = if (neon) (ApprovedHomeLayout.VERTICAL_PADDING_DP + ApprovedHomeLayout.EXTRA_VERTICAL_PADDING_DP).dp else 8.dp,
                bottom = if (neon) (ApprovedHomeLayout.VERTICAL_PADDING_DP + ApprovedHomeLayout.EXTRA_VERTICAL_PADDING_DP).dp else 8.dp,
            )

    Column(
        verticalArrangement = Arrangement.spacedBy(dividerGap),
        modifier =
            modifier.reportDetailRowHeight().keepTitledRowVisible().focusProperties {
                onEnter = {
                    focusRequester.tryRequestFocus()
                }
            },
    ) {
        ItemRowTitle(
            title,
            kicker = titleKicker,
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

        FocusSafeLazyRow(
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
 * A row's title. Neon Board (`02-components-tv.md` § T4): Audiowide 400 22sp
 * uppercase, an optional count on the right, then a 1dp rule in the row accent with
 * 14dp to the cards. Stock title on every other theme.
 */
@Composable
fun ItemRowTitle(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    accent: Color = LocalNeonAccent.current,
    dividerGap: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.TITLE_DIVIDER_GAP_DP.dp else 4.dp,
    dividerThickness: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.DIVIDER_THICKNESS_DP.dp else 1.dp,
    dividerGlow: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.DIVIDER_GLOW_DP.dp else 12.dp,
    dividerGlowStrength: Float = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.DIVIDER_GLOW_STRENGTH / 100f else .35f,
    titleSize: TextUnit = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.TITLE_SIZE_SP.sp else 22.sp,
    titleLetterSpacing: TextUnit =
        if (BuildConfig.FLAVOR ==
            "weaselfin"
        ) {
            (ApprovedHomeLayout.TITLE_TRACKING_TENTHS_SP / 10f).sp
        } else {
            .06.em
        },
    countSize: TextUnit = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.COUNT_SIZE_SP.sp else 12.sp,
    countOpacity: Float = 1f,
    countEndPadding: Dp = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.COUNT_END_PADDING_DP.dp else 8.dp,
    titleStartPadding: Dp = 8.dp,
    kicker: String? = null,
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
            Column(
                modifier = Modifier.weight(1f).offset(x = titleStartPadding.coerceAtMost(0.dp)),
            ) {
                if (!kicker.isNullOrBlank()) {
                    Text(
                        text = kicker.uppercase(),
                        style = NeonType.homeKicker(titleSize),
                        color = neonGlowAccent(accent),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = title.uppercase(),
                    style =
                        NeonType.sectionTitle().copy(
                            fontSize = titleSize,
                            lineHeight = titleSize * 1.2f,
                            letterSpacing = titleLetterSpacing,
                        ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (count != null && count > 0) {
                Text(
                    text = count.toString(),
                    style = NeonType.numeral(countSize).copy(fontSize = countSize, lineHeight = countSize * 1.2f),
                    color = Color(0xFF787E8C).copy(alpha = countOpacity.coerceIn(0f, 1f)),
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
