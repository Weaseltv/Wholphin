package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
class LicenseLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `first and final dependencies fit and license details open with a remote click`() {
        val libraries =
            List(50) {
                DependencyAttribution(
                    id = "dependency-$it",
                    name = "Dependency $it",
                    description = null,
                    website = null,
                    authors = "Example author",
                    version = "1.2.3",
                    licenses = listOf(DependencyLicense("Test license", "Full license body", null)),
                )
            }
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) { LicenseInfoContent(libraries, Modifier.fillMaxSize()) }
        }
        for (label in listOf("Dependency 0", "Dependency 49")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val entry = compose.onNode(hasText(label) and isFocusable())
            entry.requestFocus()
            val bounds = entry.getUnclippedBoundsInRoot()
            assertTrue("Complete focused attribution must clear the page: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
        }
        compose.onNode(hasText("Dependency 49") and isFocusable()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Full license body").assertIsDisplayed()
    }
}
