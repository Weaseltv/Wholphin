package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.main.settings.RemovedItem
import com.github.damontecres.wholphin.ui.main.settings.RemovedNextUpContent
import com.github.damontecres.wholphin.ui.main.settings.RemovedNextUpContentViewModel
import com.github.damontecres.wholphin.ui.main.settings.RemovedNextUpState
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.DataLoadingState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemovedNextUpLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `removed next up retains its full final row and bounded heading at enlarged text`() {
        val removed =
            List(30) { i ->
                RemovedItem(
                    BaseItem(
                        BaseItemDto(id = UUID.randomUUID(), name = "Removed series $i with a longer title", type = BaseItemKind.SERIES),
                    ),
                    null,
                    LocalDateTime.now(),
                )
            }
        val vm = mockk<RemovedNextUpContentViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(RemovedNextUpState(DataLoadingState.Success(removed)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    RemovedNextUpContent(Modifier.size(500.dp, 480.dp), vm)
                }
            }
        }
        compose.onNode(hasText("Removed series 0 with a longer title") and isFocusable()).requestFocus()
        repeat(29) {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
        }
        compose.onNodeWithText("Removed series 29 with a longer title").assertIsDisplayed()
        val b = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue("Final removed item fits: $b", b.top.value >= 0 && b.bottom.value <= 480)
    }
}
