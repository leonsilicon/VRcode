package dev.improve.vrcode

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The dock's two panels at the headset's pixel scale (about 0.83 mm per pixel): the 10 x 5 cm bar
 * and the 48 x 17 cm raised body. Captures go to the test app's external `screenshots` directory.
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
    var swipe by mutableStateOf(0f)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(120.dp, 60.dp)) { DockBar(bulge, swipe) }
      }
    }
    compose.onNodeWithTag("vrcode-dock-bar").assertIsDisplayed()
    compose.onNodeWithText("⌃").assertDoesNotExist()
    capture("dock-bar-rest.png", "vrcode-dock-bar")
    compose.runOnIdle { bulge = 1f }
    compose.onNodeWithText("⌃").assertExists()
    capture("dock-bar-bulge.png", "vrcode-dock-bar")
    compose.runOnIdle { swipe = 0.7f }
    capture("dock-bar-swipe.png", "vrcode-dock-bar")
  }

  @Test fun theRaisedDockShowsItsOptionsGrabberAndTheFlickCue() {
    var pressing by mutableStateOf(false)
    var lift by mutableStateOf(0f)
    var hint by mutableStateOf<String?>("Press an icon, slide, then flick up")
    val xs = options.indices.map { (it - (options.size - 1) / 2f) * 0.06f }.map { (it + 0.24f) / 0.48f }
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(576.dp, 204.dp)) {
          DockBody(options, xs, highlighted = 3, pressing = pressing, fingerX = xs[3], lift = lift, grabberBulge = 0f,
            grabberSwipe = 0f, rowY = 0.76f, grabberY = 0.47f, bodyTop = 0.35f, barFraction = 0.07f / 0.48f, hint = hint)
        }
      }
    }
    compose.onNodeWithText("Keyboard").assertExists()
    compose.onNodeWithText("Press an icon, slide, then flick up").assertExists()
    capture("dock-raised.png")
    compose.runOnIdle {
      pressing = true
      lift = 0.6f
      hint = null
    }
    compose.onNodeWithText("↑ Keyboard").assertExists()
    capture("dock-raised-flick.png")
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
