package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.ui.playback.NextUpEpisode
import com.github.damontecres.wholphin.ui.setup.InstallUpdatePageContent
import com.github.damontecres.wholphin.ui.setup.JellyfinUserAndImage
import com.github.damontecres.wholphin.ui.setup.UserList
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.Version
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuxiliaryLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `next episode description yields space to its title and runtime`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    NextUpEpisode(
                        "A long episode title with several words",
                        "Long overview ".repeat(40),
                        null,
                        {},
                        null,
                        Modifier.width(800.dp).height(216.dp),
                        runtime = 22.minutes,
                    )
                }
            }
        }
        compose.onNodeWithText("A long episode title with several words").assertIsDisplayed()
        val bounds = compose.onNodeWithText("Long overview ".repeat(40)).getUnclippedBoundsInRoot()
        assertTrue("Description is bounded by the Next Up panel: $bounds", bounds.bottom.value <= 200)
    }

    @Test
    fun `update actions and long notes remain inside the smaller viewport`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    InstallUpdatePageContent(
                        Version(1, 2, 29),
                        Release(
                            Version(1, 2, 30),
                            null,
                            null,
                            "- A readable release note.\n".repeat(80),
                            emptyList(),
                        ),
                        {},
                        {},
                        Modifier.fillMaxSize().padding(24.dp),
                    )
                }
            }
        }
        for (label in listOf("Download & Update", "Cancel")) {
            val action = compose.onNode(hasText(label) and isFocusable())
            action.requestFocus()
            action.assertIsDisplayed()
            val b = action.getUnclippedBoundsInRoot()
            assertTrue("$label inside viewport: $b", b.top.value >= 0 && b.bottom.value <= 540 && b.right.value <= 800)
        }
    }

    @Test
    fun `last profile and add profile tile retain their entire caption`() {
        val users =
            List(12) {
                JellyfinUserAndImage(
                    JellyfinUser(
                        id = UUID.randomUUID(),
                        serverId = UUID.randomUUID(),
                        name = "Profile ${it + 1} with a longer name",
                        accessToken = null,
                    ),
                    null,
                    true,
                )
            }
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.fillMaxSize().padding(24.dp)) { UserList(users, null, {}, {}, {}, null) }
            }
        }
        for (label in listOf("Profile 12 with a longer name", "Add User")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
            val b = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("Full caption fits: $b", b.left.value >= 0 && b.right.value <= 800 && b.bottom.value <= 540)
        }
    }
}
