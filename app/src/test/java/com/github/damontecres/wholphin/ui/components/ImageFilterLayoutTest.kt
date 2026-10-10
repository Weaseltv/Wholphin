package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.MusicServiceState
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.detail.PlaylistItems
import com.github.damontecres.wholphin.ui.detail.music.LyricsContent
import com.github.damontecres.wholphin.ui.detail.music.SongListItem
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.LyricLine
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
class ImageFilterLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `narrow image and video filter panels retain every slider label and final action`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    com.github.damontecres.wholphin.ui.slideshow.ImageFilterSliders(
                        com.github.damontecres.wholphin.data.model
                            .VideoFilter(),
                        true,
                        true,
                        true,
                        {},
                        {},
                        {},
                        Modifier.width(320.dp).height(480.dp),
                    )
                }
            }
        }
        for (label in listOf(
            "Brightness",
            "Contrast",
            "Saturation",
            "Hue",
            "Red",
            "Green",
            "Blue",
            "Blur",
            "Save",
            "Save for album",
            "Reset",
        )) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
            val b = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue(
                "Filter text fits the panel: $label $b",
                b.top.value >= 0 && b.bottom.value <= 480 && b.left.value >= 0 && b.right.value <= 320,
            )
        }
        val reset = compose.onNode(hasText("Reset") and isFocusable())
        reset.requestFocus()
        val b = reset.getUnclippedBoundsInRoot()
        assertTrue("Reset focus fits the panel: $b", b.top.value >= 0 && b.bottom.value <= 480 && b.right.value <= 320)
    }
}
