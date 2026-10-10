package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.GridCard
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertTrue
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
class CardGridColumnNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun showGrid(
        count: Int = 157,
        columns: Int = 6,
        loaded: State<Boolean>? = null,
    ) {
        val items =
            List(count) { index ->
                BaseItem(
                    BaseItemDto(
                        id = UUID.randomUUID(),
                        type = BaseItemKind.MOVIE,
                        name = if (index % 4 == 0) "Movie $index with a long two line title" else "Movie $index",
                        productionYear = 2026,
                    ),
                )
            }
        compose.setContent {
            CompositionLocalProvider(LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    CardGrid(
                        pager = items.mapIndexed { index, item -> if (index >= 12 && loaded?.value == false) null else item },
                        onClickItem = { _, _ -> },
                        onLongClickItem = { _, _ -> },
                        onClickPlay = { _, _ -> },
                        letterPosition = { 0 },
                        gridFocusRequester = remember { FocusRequester() },
                        showJumpButtons = false,
                        showLetterButtons = false,
                        columns = columns,
                        modifier = Modifier.fillMaxSize(),
                        cardContent = { details ->
                            GridCard(details.item, details.onClick, details.onLongClick, details.mod.testTag("card-${details.index}"))
                        },
                    )
                }
            }
        }
        // The app initially enters the grid before the remote changes columns.
        focus(0)
    }

    private fun focus(index: Int) {
        compose.onNode(hasAnyAncestor(hasTestTag("card-$index")) and isFocusable()).requestFocus()
        compose.waitForIdle()
        assertFocus(index)
    }

    private fun assertFocus(index: Int) {
        assertTrue(
            "Expected focus on card $index: " + compose.onRoot().printToString(),
            compose.onAllNodes(hasAnyAncestor(hasTestTag("card-$index")) and isFocused()).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        compose.waitForIdle()
    }

    @Test
    fun `up and down preserve every column across ten scrolling rows`() {
        showGrid()
        for (column in 0..5) {
            focus(column)
            repeat(10) { row ->
                press(Key.DirectionDown)
                assertFocus(column + (row + 1) * 6)
            }
            repeat(10) { row ->
                press(Key.DirectionUp)
                assertFocus(column + (9 - row) * 6)
            }
        }
    }

    @Test
    fun `fast repeated keys retain the intended column before the destination is composed`() {
        showGrid()
        focus(3)
        compose.onRoot().performKeyInput {
            repeat(12) {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
        }
        compose.waitForIdle()
        assertFocus(75)
        compose.onRoot().performKeyInput {
            repeat(12) {
                keyDown(Key.DirectionUp)
                keyUp(Key.DirectionUp)
            }
        }
        compose.waitForIdle()
        assertFocus(3)
    }

    @Test
    fun `navigation uses the configured column count and follows horizontal changes`() {
        showGrid(columns = 5)
        focus(4)
        repeat(8) { press(Key.DirectionDown) }
        assertFocus(44)
        press(Key.DirectionLeft)
        assertFocus(43)
        press(Key.DirectionUp)
        assertFocus(38)
        press(Key.DirectionRight)
        assertFocus(39)
        press(Key.DirectionDown)
        assertFocus(44)
    }

    @Test
    fun `a short final row selects its last available card and stops at the edge`() {
        showGrid(count = 65)
        focus(5)
        repeat(10) { press(Key.DirectionDown) }
        assertFocus(64)
        press(Key.DirectionDown)
        assertFocus(64)
        press(Key.DirectionUp)
        assertFocus(58)
    }

    @Test
    fun `placeholder rows maintain a non-first column before and after loading`() {
        val loaded = mutableStateOf(false)
        showGrid(loaded = loaded)
        focus(2)
        repeat(10) { row ->
            press(Key.DirectionDown)
            assertFocus(2 + (row + 1) * 6)
        }
        compose.runOnIdle { loaded.value = true }
        compose.waitForIdle()
        assertFocus(62)
        press(Key.DirectionDown)
        assertFocus(68)
        press(Key.DirectionUp)
        assertFocus(62)
    }
}
