package dev.improve.vrcode

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The dock's two panels at the headset's pixel scale (about 0.83 mm per pixel): the 12 x 6 cm bar
 * and the 48 x 15 cm raised body. Captures go to the test app's external `screenshots` directory.
 */
class DockPanelTest {
  @get:Rule val compose = createComposeRule()

  private val options = listOf(
    DockOption("Connected", DockOptionKind.Connection),
    DockOption("zsh — ~/repo", DockOptionKind.Window, selected = true),
    DockOption("claude", DockOptionKind.Window),
    DockOption("Keyboard", DockOptionKind.Keyboard),
    DockOption("Arrange", DockOptionKind.Arrange),
    DockOption("Settings", DockOptionKind.Settings),
  )

  @Test fun theBarIsFaintAtRestAndBulgesWithAnUpCueUnderAHand() {
    var bulge by mutableStateOf(0f)
    var raised by mutableStateOf(false)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(144.dp, 72.dp)) { DockBar(bulge, raised) }
      }
    }
    compose.onNodeWithTag("vrcode-dock-bar").assertIsDisplayed()
    compose.onNodeWithText("⌃").assertDoesNotExist()
    capture("dock-bar-rest.png", "vrcode-dock-bar")
    compose.runOnIdle { bulge = 1f }
    compose.onNodeWithText("⌃").assertExists()
    capture("dock-bar-bulge.png", "vrcode-dock-bar")
    // Raised, the same bar stays under the options and its cue points down: touch to lower.
    compose.runOnIdle { raised = true }
    compose.onNodeWithText("⌄").assertExists()
    compose.onNodeWithText("⌃").assertDoesNotExist()
    capture("dock-bar-raised.png", "vrcode-dock-bar")
  }

  @Test fun theRaisedDockShowsTheLabelOfTheOptionAHandIsNear() {
    var highlighted by mutableStateOf<Int?>(null)
    val xs = options.indices.map { (it - (options.size - 1) / 2f) * 0.06f }.map { (it + 0.24f) / 0.48f }
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // The body panel at the headset's scale (48 x 15 cm), laid out as DockFeature does.
        Box(Modifier.requiredSize(576.dp, 180.dp)) { DockBody(options, xs, highlighted, rowY = 0.7f, bodyTop = 0.4f) }
      }
    }
    compose.onNodeWithText("Keyboard").assertDoesNotExist()
    capture("dock-raised.png")
    compose.runOnIdle { highlighted = 3 }
    compose.onNodeWithText("Keyboard").assertExists()
    capture("dock-raised-hover.png")
  }

  @Test fun touchesAreReportedOnContactBeforeAnyRelease() {
    val bar = mutableListOf<DockTouch>()
    val body = mutableListOf<DockTouch>()
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // Stacked, as in the headset: the body above the bar, never overlapping.
        Column {
          Box(Modifier.requiredSize(576.dp, 180.dp)) { DockBody(options, emptyList(), null, rowY = 0.7f, bodyTop = 0.4f) { body += it } }
          Box(Modifier.requiredSize(144.dp, 72.dp)) { DockBar(0f) { bar += it } }
        }
      }
    }
    compose.onNodeWithTag("vrcode-dock-bar").performTouchInput { down(center) }
    compose.runOnIdle {
      assertEquals(listOf(DockTouchPhase.Down), bar.map { it.phase })
      assertEquals(0.5f, bar[0].x, 0.01f)
      assertEquals(0.5f, bar[0].y, 0.01f)
    }
    compose.onNodeWithTag("vrcode-dock-bar").performTouchInput { up() }
    compose.runOnIdle { assertEquals(DockTouchPhase.Up, bar.last().phase) }
    compose.onNodeWithTag("vrcode-dock").performTouchInput { down(Offset(width * 0.25f, height * 0.7f)) }
    compose.runOnIdle {
      assertEquals(listOf(DockTouchPhase.Down), body.map { it.phase })
      assertEquals(0.25f, body[0].x, 0.01f)
      assertEquals(0.7f, body[0].y, 0.01f)
    }
  }

  private fun capture(name: String, tag: String = "vrcode-dock") {
    compose.mainClock.advanceTimeBy(1000)
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    File(directory, name).outputStream().use {
      compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
