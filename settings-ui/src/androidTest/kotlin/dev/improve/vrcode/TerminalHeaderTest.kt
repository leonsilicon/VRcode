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

  @Test fun bothStylesTargetTheirOwnScreenAndExposeFullscreenRestore() {
    val calls = mutableListOf<String>()
    var style by mutableStateOf(WindowStyle.MacOS)
    var fullscreen by mutableStateOf(false)
    compose.setContent {
      androidx.compose.foundation.layout.Column {
        for (screen in listOf("first", "second")) {
          Box(Modifier.testTag(screen)) {
            VrcodeWindowHeader(TerminalWindowState(screen).apply { loading = false }, style,
              WindowChrome(onClose = { calls += "$screen close" }, onMinimize = { calls += "$screen minimize" },
                onZoom = { calls += "$screen zoom"; fullscreen = !fullscreen }), fullscreen = fullscreen)
          }
        }
      }
    }
    for (nextStyle in WindowStyle.entries) {
      compose.runOnIdle { style = nextStyle; fullscreen = false; calls.clear() }
      fun button(screen: String, label: String) = compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag(screen)))
      button("first", "Minimize").performClick()
      button("second", "Maximize").performClick()
      button("second", "Restore window").assertIsDisplayed().performClick()
      button("second", "Close").performClick()
      button("first", "Close").performClick()
      compose.runOnIdle { assertEquals(listOf("first minimize", "second zoom", "second zoom", "second close", "first close"), calls) }
    }
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

  @OptIn(ExperimentalTestApi::class)
  @Test fun trafficLightsExpandOnHoverWithoutMovingTargetsAndClickAcrossTheWholePill() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val calls = mutableListOf<String>()
    val hover = TerminalHeaderHover()
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(900.dp, 180.dp).testTag("test-window")) {
          VrcodeWindowHeader(TerminalWindowState("main · A long terminal title stays clear of the controls").apply { loading = false },
            WindowStyle.MacOS, WindowChrome(
              onClose = { calls += "close" }, onMinimize = { calls += "minimize" }, onZoom = { calls += "zoom" },
              trailingSlot = { Row { WindowModeButton(WindowMode.Fixed) {}; SettingsButton {} } },
            ), hoverState = hover)
        }
      }
    }
    val tags = listOf("xmux-close", "xmux-minimize", "xmux-zoom")
    val targets = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
    val title = compose.onNodeWithTag("xmux-title").fetchSemanticsNode().boundsInRoot
    for (tag in tags) {
      compose.onNodeWithTag(tag).assertWidthIsAtLeast(64.dp).assertHeightIsAtLeast(48.dp)
      compose.onNodeWithTag("$tag-shape", useUnmergedTree = true).assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
    }
    capture(context, "window-lights-resting.png")
    for (tag in tags) {
      val target = compose.onNodeWithTag(tag)
      target.performMouseInput { enter(center) }
      compose.mainClock.advanceTimeBy(220)
      compose.onNodeWithTag("$tag-shape", useUnmergedTree = true).assertWidthIsEqualTo(60.dp).assertHeightIsEqualTo(44.dp)
      assertEquals(title, compose.onNodeWithTag("xmux-title").fetchSemanticsNode().boundsInRoot)
      for (other in tags) assertEquals(targets[other], compose.onNodeWithTag(other).fetchSemanticsNode().boundsInRoot)
      if (tag == "xmux-minimize") capture(context, "window-lights-hover.png")
      // Click the expanded pill's edge, well outside its resting circle.
      target.performMouseInput { click(Offset(5f, center.y)); exit() }
      compose.mainClock.advanceTimeBy(220)
      compose.onNodeWithTag("$tag-shape", useUnmergedTree = true).assertWidthIsEqualTo(28.dp)
    }
    compose.runOnIdle { assertEquals(listOf("close", "minimize", "zoom"), calls) }
    // Quest geometry-driven hover follows the same targets even without Android mouse events.
    compose.runOnIdle { hover.updatePointer(targets.getValue("xmux-close").center) }
    compose.mainClock.advanceTimeBy(220)
    compose.onNodeWithTag("xmux-close-shape", useUnmergedTree = true).assertWidthIsEqualTo(60.dp)
    compose.runOnIdle { hover.updatePointer(null) }
    compose.mainClock.advanceTimeBy(220)
    compose.onNodeWithTag("xmux-close-shape", useUnmergedTree = true).assertWidthIsEqualTo(28.dp)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test fun onlyTheFocusedWindowIsOutlinedAndUnfocusedTrafficLightsAreGrayUntilHovered() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val theme = TerminalTheme.Dracula
    var focused by mutableStateOf(true)
    val chrome = WindowChrome(
      onClose = {}, onMinimize = {}, onZoom = {},
      header = { s, c -> VrcodeWindowHeader(s, WindowStyle.MacOS, c, focused = focused) },
      outline = { windowOutline(focused, theme) },
    ).withTerminalTheme(theme)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(800.dp, 200.dp)) {
          WindowFrame(TerminalWindowState("main · Terminal").apply { loading = false }, Modifier.testTag("test-window"), chrome,
            body = { ctx -> TextView(ctx) })
        }
      }
    }
    val window = compose.onNodeWithTag("test-window").fetchSemanticsNode().boundsInRoot
    fun shape() = compose.onNodeWithTag("xmux-close-shape", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    fun pixel(x: Float, y: Float): Int =
      compose.onNodeWithTag("test-window").captureToImage().asAndroidBitmap().getPixel((x - window.left).toInt(), (y - window.top).toInt())
    fun Int.r() = (this shr 16) and 255
    fun Int.g() = (this shr 8) and 255
    fun Int.b() = this and 255
    fun Int.isRed() = r() > 200 && g() < 130 && b() < 130
    fun Int.isGray() = maxOf(r(), g(), b()) - minOf(r(), g(), b()) < 24 && r() in 70..160
    fun Int.isAccent() = r() in 170..210 && g() in 130..165 && b() > 230
    // Mid-height on the left edge, inside the focused ring but past the plain 1dp hairline.
    val edge = Offset(window.left + 1.5f, window.top + 150f)

    val active = shape()
    assertTrue("focused close light is red", pixel(active.left + 5f, active.center.y).isRed())
    assertFalse("focused close light shows its glyph", pixel(active.center.x, active.center.y).isRed())
    assertTrue("focused window has the accent outline", pixel(edge.x, edge.y).isAccent())
    capture(context, "window-focused.png")

    compose.runOnIdle { focused = false }
    val resting = shape()
    assertTrue("unfocused close light is gray", pixel(resting.left + 5f, resting.center.y).isGray())
    assertTrue("unfocused close light hides its glyph", pixel(resting.center.x, resting.center.y).isGray())
    assertFalse("unfocused window has no accent outline", pixel(edge.x, edge.y).isAccent())
    capture(context, "window-unfocused.png")

    compose.onNodeWithTag("xmux-close").performMouseInput { enter(center) }
    compose.mainClock.advanceTimeBy(220)
    val hovered = shape()
    assertTrue("hovering an unfocused light restores its color", pixel(hovered.left + 8f, hovered.center.y).isRed())
    capture(context, "window-unfocused-hover.png")
  }

  private fun capture(context: Context, name: String) {
    val bitmap = compose.onNodeWithTag("test-window").captureToImage().asAndroidBitmap()
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: context.getExternalFilesDir(null)!!
    directory.mkdirs()
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
