package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
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
class HomeViewportLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `actual home scrolling preserves a full kicker at every row`() {
        val rows =
            List(7) { row ->
                HomeRowLoadingState.Success(
                    title = StringStringProvider("Section $row"),
                    items = List(4) { BaseItem(BaseItemDto(id = UUID.randomUUID(), name = "Card $row-$it", type = BaseItemKind.MOVIE)) },
                )
            }
        val position = mutableStateOf(RowColumn(0, 0))
        val imageUrls = mockk<ImageUrlService>(relaxed = true)
        compose.setContent {
            CompositionLocalProvider(LocalImageUrlService provides imageUrls) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    HomePageContent(
                        homeRows = rows,
                        position = position.value,
                        onFocusPosition = { position.value = it },
                        onClickItem = { _, _ -> },
                        onLongClickItem = { _, _ -> },
                        onClickPlay = { _, _ -> },
                        showClock = false,
                        onUpdateBackdrop = {},
                        showLogo = false,
                        showViewMore = false,
                        modifier = Modifier.fillMaxSize(),
                        headerComposable = { item -> Box(Modifier.height(if (item?.name?.endsWith("-1") == true) 240.dp else 120.dp)) },
                        rowHeading = { it.title.getString() to "Kicker ${rows.indexOf(it)}" },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Card 0-0") and isFocusable()).requestFocus()
        compose.waitForIdle()
        repeat(6) {
            compose.onRoot().performKeyInput {
                keyDown(androidx.compose.ui.input.key.Key.DirectionDown)
                keyUp(androidx.compose.ui.input.key.Key.DirectionDown)
            }
            compose.waitForIdle()
            assertEquals("D-pad must advance to the next actual Home row", it + 1, position.value.row)
            val kicker = compose.onNodeWithText("KICKER ${position.value.row}")
            val unclipped = kicker.getUnclippedBoundsInRoot()
            val visible = kicker.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Entire focused kicker must clear the list's clipping edge: $unclipped vs $visible",
                kotlin.math.abs(unclipped.top.value - visible.top) <= 1f,
            )
            assertTrue(
                "Full kicker height must remain visible: $unclipped vs $visible",
                kotlin.math.abs((unclipped.bottom - unclipped.top).value - visible.height) <= 1f,
            )
        }
        val lastKicker =
            compose
                .onNodeWithText("KICKER 6")
                .getUnclippedBoundsInRoot()
                .top.value
        for (index in listOf(1, 2, 1, 0)) {
            compose.onNode(hasContentDescription("Card 6-$index") and isFocusable()).requestFocus()
            compose.waitForIdle()
            assertEquals(
                "Longer header copy must not move the focused row vertically",
                lastKicker,
                compose
                    .onNodeWithText("KICKER 6")
                    .getUnclippedBoundsInRoot()
                    .top.value,
                1f,
            )
        }
    }
}
