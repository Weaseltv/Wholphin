package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ErrorLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long playback and server error chains expose their final detail without sending logs`() {
        var reports = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.size(700.dp, 400.dp)) {
                        ErrorMessageContent(
                            "The media could not load. ".repeat(100),
                            IllegalStateException("Connection failed", IllegalArgumentException("Final error detail")),
                            { reports++ },
                        )
                    }
                }
            }
        }
        val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        assertTrue("Error viewport must fit its parent: $viewport", viewport.bottom.value <= 400)
        val scroll = compose.onNode(hasScrollAction())
        scroll.requestFocus()
        for (step in 0 until 100) {
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue()) break
            scroll.performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
        }
        compose.waitForIdle()
        val final = compose.onNodeWithText("Caused by: Final error detail")
        val bounds = final.getUnclippedBoundsInRoot()
        assertTrue(
            "The final error cause must be readable: $bounds / $viewport",
            bounds.top.value >= viewport.top.value && bounds.bottom.value <= viewport.bottom.value,
        )
        assertTrue("Layout verification must not send reports", reports == 0)
    }
}
