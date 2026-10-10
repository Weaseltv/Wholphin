package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.GridCard
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.detail.CardGridItem
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardGridFocusLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val originalIO = WholphinDispatchers.IO

    @Before fun useMainDispatcher() {
        WholphinDispatchers.IO = Dispatchers.Main.immediate
    }

    @After fun restoreDispatcher() {
        WholphinDispatchers.IO = originalIO
    }

    private data class Item(
        val index: Int,
    ) : CardGridItem {
        override val gridId = index.toString()
        override val playable = false
        override val sortName = ('A' + index / 6).toString()
    }

    @Test
    fun `remote page jump transfers focus to a visible destination card`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                CardGrid(
                    List(157) { Item(it) },
                    { _, _ -> },
                    { _, _ -> },
                    { _, _ -> },
                    { 0 },
                    remember { FocusRequester() },
                    false,
                    false,
                    Modifier.fillMaxSize(),
                    cardContent = { details ->
                        Button(details.onClick, details.mod.height(140.dp)) { Text("Card ${details.index}") }
                    },
                )
            }
        }
        compose.onNode(hasText("Card 0") and isFocusable()).requestFocus()
        compose.onRoot().performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.MediaFastForward)
            keyUp(androidx.compose.ui.input.key.Key.MediaFastForward)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Card 36") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Card 36").assertIsDisplayed()
    }

    @Test
    fun `alphabet jumps transfer focus to the destination card`() {
        val position = mutableStateOf(0)
        val items = List(156) { Item(it) }
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                CardGrid(
                    items,
                    { _, _ -> },
                    { _, _ -> },
                    { _, _ -> },
                    { letter -> items.indexOfFirst { it.sortName == letter.toString() } },
                    remember { FocusRequester() },
                    false,
                    true,
                    Modifier.fillMaxSize(),
                    positionCallback = { _, index -> position.value = index },
                    cardContent = { details ->
                        Button(details.onClick, details.mod.height(140.dp)) { Text("Card ${details.index}") }
                    },
                )
            }
        }
        compose.onNode(hasText("Card 0") and isFocusable()).requestFocus()
        compose.onNodeWithText("Z").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Card 150") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(150, position.value)
        compose.onNodeWithText("Card 150").assertIsDisplayed()
        compose.onNodeWithText("A").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Card 0") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, position.value)
    }

    @Test
    fun `a letter beyond the final title focuses the final loaded card`() {
        val items = List(36) { Item(it) }
        val position = mutableStateOf(0)
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                CardGrid(
                    items,
                    { _, _ -> },
                    { _, _ -> },
                    { _, _ -> },
                    { items.size },
                    remember { FocusRequester() },
                    false,
                    true,
                    Modifier.fillMaxSize(),
                    positionCallback = { _, index -> position.value = index },
                    cardContent = { details ->
                        Button(details.onClick, details.mod.height(140.dp)) { Text("Card ${details.index}") }
                    },
                )
            }
        }
        compose.onNode(hasText("Card 0") and isFocusable()).requestFocus()
        compose.onAllNodes(hasScrollAction())[1].performScrollToNode(hasText("Z"))
        compose.onNodeWithText("Z").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Card 35") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(35, position.value)
        compose.onNodeWithText("Card 35").assertIsDisplayed()
    }

    @Test
    fun `a smaller filtered result set restores focus to a valid card`() {
        val count = mutableStateOf(157)
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                CardGrid(
                    List(count.value) { Item(it) },
                    { _, _ -> },
                    { _, _ -> },
                    { _, _ -> },
                    { count.value - 1 },
                    remember { FocusRequester() },
                    false,
                    true,
                    Modifier.fillMaxSize(),
                    cardContent = { details ->
                        Button(details.onClick, details.mod.height(140.dp)) { Text("Card ${details.index}") }
                    },
                )
            }
        }
        compose.onNode(hasText("Card 0") and isFocusable()).requestFocus()
        compose.onAllNodes(hasScrollAction())[1].performScrollToNode(hasText("Z"))
        compose.onNodeWithText("Z").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Card 156") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { count.value = 3 }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Card 2") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Card 2").assertIsDisplayed()
    }

    @Test
    fun `a letter jump waits for paging and reveals the loaded poster with its full caption`() {
        val loaded = mutableStateOf(false)
        val imageUrls = mockk<ImageUrlService>(relaxed = true)
        val showHeader = mutableStateOf(true)
        val allItems =
            List(157) { index ->
                BaseItem(
                    BaseItemDto(
                        id = UUID(0, index.toLong()),
                        name = if (index == 156) "Zarna Garg: Practical People Win" else "Card $index",
                        type = BaseItemKind.MOVIE,
                        productionYear = 2025,
                    ),
                )
            }
        compose.setContent {
            CompositionLocalProvider(LocalImageUrlService provides imageUrls) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Column(Modifier.fillMaxSize()) {
                        AnimatedVisibility(showHeader.value) { Box(Modifier.height(200.dp)) }
                        CardGrid(
                            pager = allItems.mapIndexed { index, item -> if (index >= 150 && !loaded.value) null else item },
                            onClickItem = { _, _ -> },
                            onLongClickItem = { _, _ -> },
                            onClickPlay = { _, _ -> },
                            letterPosition = { letter -> if (letter == 'Z') 156 else 0 },
                            gridFocusRequester = remember { FocusRequester() },
                            showJumpButtons = false,
                            showLetterButtons = true,
                            modifier = Modifier.fillMaxSize(),
                            positionCallback = { _, index -> showHeader.value = index < 6 },
                            cardContent = { details ->
                                GridCard(
                                    details.item,
                                    details.onClick,
                                    details.onLongClick,
                                    modifier = details.mod.testTag("poster-${details.index}"),
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.onNode(hasAnyAncestor(hasTestTag("poster-0")) and isFocusable()).requestFocus()
        compose.onAllNodes(hasScrollAction())[1].performScrollToNode(hasText("Z"))
        compose.onNodeWithText("Z").performClick()
        compose.waitForIdle()
        compose.runOnIdle { loaded.value = true }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasAnyAncestor(hasTestTag("poster-156")) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        val caption = compose.onNodeWithText("Zarna Garg: Practical People Win").getUnclippedBoundsInRoot()
        val year =
            compose
                .onAllNodes(hasText("2025"))
                .fetchSemanticsNodes()
                .last()
                .boundsInRoot
        assertTrue("Loaded caption must clear the bottom edge: $caption", caption.bottom.value < 540)
        assertTrue("Loaded year must remain visible: $year", year.bottom < 540 && year.height > 0)
    }

    @Test
    fun `a focused placeholder stays visible when its caption arrives after scrolling`() {
        val loaded = mutableStateOf(false)
        val lastIndex = 156
        val item =
            BaseItem(
                BaseItemDto(
                    id = UUID.randomUUID(),
                    type = BaseItemKind.MOVIE,
                    name = "A loaded movie with a long two line title",
                    productionYear = 2026,
                ),
            )
        compose.setContent {
            CompositionLocalProvider(LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    CardGrid(
                        pager = List(157) { index -> if (index == lastIndex && !loaded.value) null else item },
                        onClickItem = { _, _ -> },
                        onLongClickItem = { _, _ -> },
                        onClickPlay = { _, _ -> },
                        letterPosition = { 0 },
                        gridFocusRequester = remember { FocusRequester() },
                        showJumpButtons = false,
                        showLetterButtons = false,
                        initialPosition = 144,
                        modifier = Modifier.fillMaxSize(),
                        cardContent = { details ->
                            GridCard(details.item, details.onClick, details.onLongClick, details.mod.testTag("late-${details.index}"))
                        },
                    )
                }
            }
        }
        compose.onNode(hasAnyAncestor(hasTestTag("late-144")) and isFocusable()).requestFocus()
        repeat(2) {
            compose.onRoot().performKeyInput {
                keyDown(androidx.compose.ui.input.key.Key.DirectionDown)
                keyUp(androidx.compose.ui.input.key.Key.DirectionDown)
            }
            compose.waitForIdle()
        }
        assertTrue(
            "Placeholder is focused before loading",
            compose.onAllNodes(hasAnyAncestor(hasTestTag("late-$lastIndex")) and isFocused()).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.runOnIdle { loaded.value = true }
        compose.waitForIdle()
        val bounds = compose.onNode(hasTestTag("late-$lastIndex")).getUnclippedBoundsInRoot()
        assertTrue(
            "The loaded card and caption fit without another D-pad movement: $bounds",
            bounds.top.value >= 0 && bounds.bottom.value < 540,
        )
    }
}
