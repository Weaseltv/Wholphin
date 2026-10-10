package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.detail.discover.ChooseFolder
import com.github.damontecres.wholphin.ui.detail.discover.ChooseProfile
import com.github.damontecres.wholphin.ui.detail.discover.ClickSwitch
import com.github.damontecres.wholphin.ui.detail.discover.SeerrProfile
import com.github.damontecres.wholphin.ui.detail.discover.SeerrRootFolder
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
class RequestOptionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long season selection labels retain their final line in a narrow request panel`() {
        val label = "Season 1: Family Vacation Extra Specials Collection"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.width(240.dp)) { ClickSwitch(label, false, {}) }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertTrue("The season selector must measure every label line", result.size.height >= result.getLineBottom(result.lineCount - 1))
    }

    @Test
    fun `long quality profile and folder menus preserve complete focused entries`() {
        val folders = mutableStateOf(false)
        val profiles = List(30) { SeerrProfile(it, "Profile ${it + 1}: High quality with descriptive long configuration name", it == 0) }
        val roots = List(30) { SeerrRootFolder(it, "/media/library-${it + 1}/long-folder-name", "100 GB", it == 0) }
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(440.dp)) {
                    if (folders.value) {
                        ChooseFolder(roots.first(), roots, {})
                    } else {
                        ChooseProfile(profiles.first(), profiles, {})
                    }
                }
            }
        }
        for (folder in listOf(false, true)) {
            compose.runOnIdle { folders.value = folder }
            val opener = compose.onNode(hasText(if (folder) "Root folder" else "Quality profile") and isFocusable())
            opener.requestFocus()
            opener.performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            val first = if (folder) roots.first().path else profiles.first().name
            val last = if (folder) roots.last().path else profiles.last().name
            for (label in listOf(first, last)) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                compose.onNode(hasText(label) and isFocusable()).requestFocus()
                compose.onNodeWithText(label).assertIsDisplayed()
                val bounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
                assertTrue("$label remains fully inside the screen: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
            }
        }
    }
}
