package com.github.damontecres.wholphin.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingArtworkLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `entire illustration fits the full area in every loading shape`() {
        val dimensions = mutableStateOf(DpSize(320.dp, 180.dp))
        val edgePainter =
            object : Painter() {
                override val intrinsicSize = Size(1600f, 900f)

                override fun DrawScope.onDraw() {
                    // Green edges expose cropping; the red center alone would hide the regression.
                    drawRect(Color.Green)
                    drawRect(
                        Color.Red,
                        topLeft = Offset(size.width * .03f, size.height * .03f),
                        size = Size(size.width * .94f, size.height * .94f),
                    )
                }
            }
        compose.setContent {
            Box(Modifier.size(dimensions.value).testTag("stage")) {
                NeonLoadingArtwork(edgePainter)
            }
        }

        // Full screen, sidebar, below details, and the 40%-width Settings-style panel.
        listOf(DpSize(320.dp, 180.dp), DpSize(280.dp, 180.dp), DpSize(280.dp, 108.dp), DpSize(112.dp, 180.dp)).forEach { size ->
            compose.runOnIdle { dimensions.value = size }
            val pixels = compose.onNodeWithTag("stage").captureToImage().toPixelMap()
            var minX = pixels.width
            var maxX = -1
            var minY = pixels.height
            var maxY = -1
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (color.green > .9f && color.red < .1f && color.blue < .1f) {
                        minX = minOf(minX, x)
                        maxX = maxOf(maxX, x)
                        minY = minOf(minY, y)
                        maxY = maxOf(maxY, y)
                    }
                }
            }
            val width = min(pixels.width.toFloat(), pixels.height * 16f / 9f)
            val height = width * 9f / 16f
            assertEquals("artwork width in $size", width, (maxX - minX + 1).toFloat(), 2f)
            assertEquals("artwork height in $size", height, (maxY - minY + 1).toFloat(), 2f)
            assertEquals("centered horizontally in $size", pixels.width / 2f, (minX + maxX + 1) / 2f, 1f)
            assertEquals("centered vertically in $size", pixels.height / 2f, (minY + maxY + 1) / 2f, 1f)
        }
    }
}
