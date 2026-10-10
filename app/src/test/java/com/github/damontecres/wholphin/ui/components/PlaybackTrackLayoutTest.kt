package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackTrackInfo
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.TrackSupport
import com.github.damontecres.wholphin.util.TrackSupportReason
import com.github.damontecres.wholphin.util.TrackType
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
class PlaybackTrackLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `expanded debug tracks retain their final row and hide action at larger text`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    PlaybackTrackInfo(
                        List(30) { index ->
                            TrackSupport(
                                index.toString(),
                                TrackType.TEXT,
                                TrackSupportReason.HANDLED,
                                index == 0,
                                listOf("Language $index — descriptive subtitle track"),
                                "srt",
                                Format.Builder().build(),
                            )
                        },
                        Modifier.fillMaxSize().padding(16.dp),
                        MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    )
                }
            }
        }
        val expand = compose.onNode(hasText("Show 29 more") and isFocusable())
        expand.requestFocus()
        expand.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        val last = "Language 29 — descriptive subtitle track"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(last))
        compose.onNode(hasText(last) and isFocusable()).requestFocus()
        compose.onNodeWithText(last).assertIsDisplayed()
        val b = compose.onNode(hasText(last) and isFocusable()).getUnclippedBoundsInRoot()
        assertTrue("Final track fits: $b", b.top.value >= 0 && b.bottom.value <= 540)
        val action = compose.onNode(hasText("Hide") and isFocusable())
        action.requestFocus()
        val a = action.getUnclippedBoundsInRoot()
        assertTrue("Hide button fits: $a", a.top.value >= 0 && a.bottom.value <= 540)
    }
}
