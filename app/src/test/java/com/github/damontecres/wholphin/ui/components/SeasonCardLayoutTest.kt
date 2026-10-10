package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeasonCardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `season and search cards bring their caption into the viewport with the image`() {
        val title = "A long season and search result title"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(450.dp)) }
                        item {
                            SeasonCard(title, "2026", title, null, false, false, 0, 0.0, 0, {}, {}, imageHeight = 140.dp)
                        }
                        item { Spacer(Modifier.height(100.dp)) }
                    }
                }
            }
        }
        compose.onNode(hasContentDescription(title) and isFocusable()).requestFocus()
        compose.waitForIdle()
        val caption = compose.onNodeWithText("2026").getUnclippedBoundsInRoot()
        val visible = compose.onNodeWithText("2026").fetchSemanticsNode().boundsInRoot
        assertTrue("Full caption clears the viewport: $caption vs $visible", caption.bottom.value <= 540 && visible.height > 0)
    }
}
