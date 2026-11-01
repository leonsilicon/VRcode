package dev.improve.vrcode

import android.content.Context
import android.graphics.Bitmap
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.xmux.window.frame.WindowFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalHeaderTest {
  @get:Rule val compose = createComposeRule()

  @Test fun savedStyleAndLiveChangesKeepMountedTerminalAndHeaderState() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("window-style-test", Context.MODE_PRIVATE)
    preferences.edit().clear().putString("window_style", "unknown-old-value").commit()
    val store = SettingsStore(preferences)
    assertEquals(WindowStyle.MacOS, store.load().windowStyle)
    store.save(AppSettings(windowStyle = WindowStyle.Windows))
    var settings by mutableStateOf(SettingsStore(preferences).load())
    var mode by mutableStateOf(WindowMode.Fixed)
    val state = TerminalWindowState("main · Terminal").apply { loading = false }
    var bodyCreations = 0
    val chrome = WindowChrome(
      trailingSlot = {
        Row {
          WindowModeButton(mode) { mode = if (mode == WindowMode.Fixed) WindowMode.Follow else WindowMode.Fixed }
          Box(Modifier.testTag("header-settings")) { SettingsButton {} }
          Text("Mic", Modifier.testTag("header-mic"), color = Color.LightGray)
        }
      },
      header = { s, c -> VrcodeWindowHeader(s, settings.windowStyle, c) },
    )
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(800.dp, 160.dp)) {
          WindowFrame(state, Modifier.testTag("test-window"), chrome,
            body = { ctx -> bodyCreations++; TextView(ctx).apply { text = "Live terminal body"; setTextColor(android.graphics.Color.LTGRAY) } })
        }
      }
    }
    compose.onNodeWithTag("vrcode-windows-header").assertIsDisplayed()
    compose.onNodeWithTag("header-settings").assertIsDisplayed()
    compose.onNodeWithTag("header-mic").assertIsDisplayed()
    compose.onNodeWithTag("xmux-spinner").assertDoesNotExist()
    compose.onNodeWithContentDescription("Close").assertIsNotEnabled().assertHasNoClickAction()
    val title = compose.onNodeWithTag("xmux-title").fetchSemanticsNode().boundsInRoot
    val minimize = compose.onNodeWithTag("xmux-minimize").fetchSemanticsNode().boundsInRoot
    val maximize = compose.onNodeWithTag("xmux-zoom").fetchSemanticsNode().boundsInRoot
    val close = compose.onNodeWithTag("xmux-close").fetchSemanticsNode().boundsInRoot
    assertTrue(title.right <= minimize.left && minimize.right <= maximize.left && maximize.right <= close.left)
    assertActionsOnRight()
    compose.onNodeWithTag("vrcode-window-mode").assertIsOff().performClick().assertIsOn()
    compose.onNodeWithTag("vrcode-window-mode").assertTextEquals("Follow")
    capture(context, "window-windows.png")

    compose.runOnIdle {
      settings = settings.copy(windowStyle = WindowStyle.MacOS)
      state.title = "A long terminal title that should truncate before the mode and settings buttons even while loading"
      state.loading = true
    }
    compose.onNodeWithTag("vrcode-windows-header").assertDoesNotExist()
    compose.onNodeWithTag("xmux-header").assertIsDisplayed()
    compose.onNodeWithTag("xmux-title").assertTextEquals(state.title)
    compose.onNodeWithTag("xmux-spinner").assertIsDisplayed()
    compose.onNodeWithTag("header-settings").assertIsDisplayed()
    compose.onNodeWithTag("header-mic").assertIsDisplayed()
    assertActionsOnRight()
    val lights = compose.onNodeWithTag("xmux-zoom").fetchSemanticsNode().boundsInRoot
    val titleBounds = compose.onNodeWithTag("xmux-title").fetchSemanticsNode().boundsInRoot
    assertTrue(lights.right <= titleBounds.left)
    compose.onNodeWithTag("vrcode-window-mode").assertIsOn().performClick().assertIsOff()
    compose.onNodeWithTag("vrcode-window-mode").assertTextEquals("Fixed")
    compose.runOnIdle { assertEquals(1, bodyCreations) }
    capture(context, "window-macos.png")

    compose.runOnIdle { settings = settings.copy(windowStyle = WindowStyle.Windows) }
    compose.onNodeWithTag("vrcode-windows-header").assertIsDisplayed()
    compose.onNodeWithTag("xmux-title").assertTextEquals(state.title)
    compose.onNodeWithTag("xmux-spinner").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, bodyCreations) }
    preferences.edit().clear().commit()
  }

  private fun assertActionsOnRight() {
    val title = compose.onNodeWithTag("xmux-title").fetchSemanticsNode().boundsInRoot
    val toggle = compose.onNodeWithTag("vrcode-window-mode").fetchSemanticsNode().boundsInRoot
    val settings = compose.onNodeWithTag("header-settings").fetchSemanticsNode().boundsInRoot
    assertTrue("Mode and settings follow the title", title.right <= toggle.left && toggle.right <= settings.left)
  }

  @Test fun windowsControlsUseTheExistingWindowActions() {
    val calls = mutableListOf<String>()
    compose.setContent {
      VrcodeWindowHeader(TerminalWindowState("Terminal").apply { loading = false }, WindowStyle.Windows,
        WindowChrome(onClose = { calls += "close" }, onMinimize = { calls += "minimize" }, onZoom = { calls += "zoom" }))
    }
    compose.onNodeWithContentDescription("Minimize").performClick()
    compose.onNodeWithContentDescription("Maximize").performClick()
    compose.onNodeWithContentDescription("Close").performClick()
    compose.runOnIdle { assertEquals(listOf("minimize", "zoom", "close"), calls) }
  }

  @Test fun bothTitleBarStylesDragWithoutTakingInputFromButtonsOrTerminalBody() {
    var style by mutableStateOf(WindowStyle.MacOS)
    val moves = mutableListOf<Pair<Offset, Offset>>()
    val clicks = mutableListOf<String>()
    val onDrag: (Offset, Offset) -> Unit = { anchor, position -> moves += anchor to position }
    val chrome = WindowChrome(
      onClose = { clicks += "close" }, onMinimize = { clicks += "minimize" }, onZoom = { clicks += "zoom" },
      trailingSlot = {
        Row {
          WindowModeButton(WindowMode.Fixed) { clicks += "mode" }
          SettingsButton { clicks += "settings" }
        }
      },
      header = { state, c -> VrcodeWindowHeader(state, style, c, onDrag) },
    )
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(800.dp, 220.dp)) {
          WindowFrame(TerminalWindowState("Terminal").apply { loading = false }, chrome = chrome,
            body = { context -> TextView(context).apply { text = "Terminal body"; minHeight = 160 } })
        }
      }
    }
    for (nextStyle in WindowStyle.entries) {
      compose.runOnIdle { style = nextStyle; moves.clear(); clicks.clear() }
      val header = compose.onNodeWithTag("vrcode-title-bar")
      header.performTouchInput { down(center); moveBy(Offset(80f, 12f)); up() }
      compose.runOnIdle {
        assertTrue("$nextStyle header should drag", moves.isNotEmpty())
        val (anchor, end) = moves.last()
        assertEquals(80f, end.x - anchor.x, 0.1f)
        assertEquals(12f, end.y - anchor.y, 0.1f)
        moves.clear()
      }
      // A tap or a small tremor on the title is not a move.
      header.performTouchInput { down(center); moveBy(Offset(1f, 1f)); up() }
      compose.onNodeWithTag("xmux-grid").performTouchInput {
        down(center); moveBy(Offset(80f, 12f)); up()
      }
      for (tag in listOf("xmux-close", "xmux-minimize", "xmux-zoom")) {
        compose.onNodeWithTag(tag).performTouchInput { down(center); moveBy(Offset(80f, 12f)); up() }
        compose.onNodeWithTag(tag).performTouchInput { click(center) }
      }
      compose.onNodeWithContentDescription("Open VRcode settings").performTouchInput {
        down(center); moveBy(Offset(80f, 12f)); up()
      }
      compose.onNodeWithContentDescription("Open VRcode settings").performTouchInput { click(center) }
      compose.onNodeWithTag("vrcode-window-mode").performTouchInput {
        down(center); moveBy(Offset(80f, 12f)); up()
      }
      compose.onNodeWithTag("vrcode-window-mode").performTouchInput { click(center) }
      compose.runOnIdle {
        assertTrue("$nextStyle buttons and body must not move the window", moves.isEmpty())
        assertEquals(listOf("close", "minimize", "zoom", "settings", "mode"), clicks)
      }
      // Cancellation must let the next gesture acquire its own grab point.
      header.performTouchInput { down(center); moveBy(Offset(-60f, 0f)); cancel() }
      compose.runOnIdle { assertTrue(moves.isNotEmpty()); moves.clear() }
      header.performTouchInput { down(center); moveBy(Offset(50f, 0f)); up() }
      compose.runOnIdle { assertEquals(50f, moves.last().second.x - moves.last().first.x, 0.1f) }
    }
  }

  private fun capture(context: Context, name: String) {
    val bitmap = compose.onNodeWithTag("test-window").captureToImage().asAndroidBitmap()
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: context.getExternalFilesDir(null)!!
    directory.mkdirs()
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
