package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.cards.DiscoverPersonRow
import com.github.damontecres.wholphin.ui.cards.PersonCard
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
class CastLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `focused cast portrait retains its complete name and role below the image`() {
        val name = "Christopher McDonald"
        val role = "Harvey Dent (voice)"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    LazyColumn {
                        item { Spacer(Modifier.height(420.dp)) }
                        item { PersonCard(name, role, null, false, {}, {}, Modifier.width(108.dp)) }
                    }
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        compose.onNode(isFocusable()).requestFocus()
        compose.waitForIdle()
        val bounds = compose.onNodeWithText(role).getUnclippedBoundsInRoot()
        assertTrue("The full cast caption must fit after portrait focus: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
    }

    @Test
    fun `request cast heading uses the same uppercase row title as local details`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                DiscoverPersonRow(
                    listOf(
                        DiscoverItem(
                            1,
                            SeerrItemType.PERSON,
                            "Christopher McDonald",
                            null,
                            null,
                            SeerrAvailability.UNKNOWN,
                            null,
                            null,
                            null,
                            null,
                            null,
                        ),
                    ),
                    {
                    },
                    Modifier.width(800.dp),
                )
            }
        }
        compose.onNodeWithText("PEOPLE").assertExists()
    }
}
