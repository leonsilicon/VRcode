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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DockPanelTest {
  @get:Rule val compose = createComposeRule()

  @Test fun focusesLiveWindowsSharesKeyboardStateAndKeepsActionsReachableWithOverflow() {
    var windows by mutableStateOf((1..10).map { DockWindow(it, if (it == 1) "A very long terminal title that must truncate" else "Terminal $it") })
    var focused by mutableStateOf<Int?>(1)
    var keyboard by mutableStateOf(false)
    val calls = mutableListOf<String>()
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(660.dp, 96.dp)) {
          DockPanel(windows, focused, true, keyboard,
            onWindow = { focused = it; calls += "window:$it" }, onKeyboard = { keyboard = !keyboard },
            onArrange = { calls += "arrange" }, onSettings = { calls += "settings" }, onConnection = { calls += "connection" })
        }
      }
    }
    compose.onNodeWithTag("dock-window-1").assertIsSelected()
    capture("dock-connected.png")
    compose.onNodeWithTag("dock-window-10").performScrollTo().performClick().assertIsSelected()
    compose.onNodeWithTag("dock-keyboard").assertIsDisplayed().assertIsOff().performClick().assertIsOn()
    compose.onNodeWithTag("dock-keyboard").performClick().assertIsOff()
    // State changes from the pie/keyboard close button are reflected here too.
    compose.runOnIdle { keyboard = true }
    compose.onNodeWithTag("dock-keyboard").assertIsOn()
    compose.onNodeWithTag("dock-arrange").assertIsDisplayed().performClick()
    compose.onNodeWithTag("dock-settings").assertIsDisplayed().performClick()
    compose.onNodeWithTag("dock-connection").performClick()
    compose.runOnIdle {
      assertEquals(listOf("window:10", "arrange", "settings", "connection"), calls)
      windows = listOf(DockWindow(1, "Renamed on Mac"))
      focused = 1
    }
    compose.onNodeWithTag("dock-window-10").assertDoesNotExist()
    compose.onNodeWithTag("dock-window-1").performScrollTo().assertIsSelected().assertTextContains("Renamed on Mac")
  }

  @Test fun disconnectedAndEmptyStatesNeverDispatchTerminalActionsButKeepRecoveryAvailable() {
    var connected by mutableStateOf(false)
    var windows by mutableStateOf(listOf(DockWindow(1, "Offline terminal")))
    val calls = mutableListOf<String>()
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(540.dp, 96.dp)) {
          DockPanel(windows, null, connected, false, onWindow = { calls += "window" }, onKeyboard = { calls += "keyboard" },
            onArrange = { calls += "arrange" }, onSettings = { calls += "settings" }, onConnection = { calls += "connection" })
        }
      }
    }
    compose.onNodeWithTag("dock-window-1").assertIsNotEnabled().performTouchInput { click(center) }
    compose.onNodeWithTag("dock-arrange").assertIsNotEnabled().performTouchInput { click(center) }
    compose.runOnIdle { windows = emptyList() }
    compose.onNodeWithText("Connect your Mac").assertIsDisplayed()
    capture("dock-offline.png")
    compose.onNodeWithTag("dock-connection").performClick()
    compose.onNodeWithTag("dock-settings").performClick()
    compose.onNodeWithTag("dock-keyboard").performClick()
    compose.runOnIdle { assertEquals(listOf("connection", "settings", "keyboard"), calls) }
    compose.runOnIdle { connected = true }
    compose.onNodeWithText("No terminal windows").assertIsDisplayed()
    compose.onNodeWithTag("dock-arrange").assertIsNotEnabled()
  }

  @Test fun collapsedHandleShowsConnectionAndOpensTheDock() {
    var connected by mutableStateOf(false)
    var expands = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // The headset mounts the 13 x 3 cm handle at the same pixel scale as the dock.
        Box(Modifier.requiredSize(158.dp, 37.dp)) { DockPill(connected) { expands++ } }
      }
    }
    compose.onNodeWithContentDescription("Show dock. Mac not connected").assertIsDisplayed()
    capture("dock-pill-offline.png", "vrcode-dock-pill")
    compose.runOnIdle { connected = true }
    compose.onNodeWithTag("vrcode-dock-pill").assertContentDescriptionEquals("Show dock. Mac connected").performClick()
    capture("dock-pill-connected.png", "vrcode-dock-pill")
    compose.runOnIdle { assertEquals(1, expands) }
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
