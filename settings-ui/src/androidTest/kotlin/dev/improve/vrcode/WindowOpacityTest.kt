package dev.improve.vrcode

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.connect.panel.ConnectStatus
import dev.improve.xmux.screen.layout.LayoutTree
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.xmux.window.frame.WindowFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class WindowOpacityTest {
  @get:Rule val compose = createComposeRule()

  /**
   * A terminal window built like VRcode's (clear frame, screen view painting the body) over a red
   * "room": the title bar, the pane and the body around it each take exactly one translucent
   * layer, while a cell the app colors stays solid. At 100% nothing of the room shows.
   */
  @Test fun aTranslucentWindowShowsTheRoomThroughOneLayerAndKeepsAppColorsSolid() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val theme = TerminalTheme.Dracula
    var opacity by mutableFloatStateOf(0.5f)
    lateinit var screen: ScreenView
    val font = TerminalFont(context, 25f)
    val grid = CellGrid(16, 4).apply {
      write(1, 0, "$ ls")
      write(3, 0, "    ", bg = 0xFF00FF00.toInt())
      setCursorVisible(false)
    }
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // Clear of the test activity's action bar, which shades the top of a capture.
        Column {
          Spacer(Modifier.height(120.dp))
          Box(Modifier.requiredSize(640.dp, 420.dp).background(Color.Red).testTag("room")) {
            WindowFrame(remember { TerminalWindowState("Opacity").apply { loading = false } }, Modifier.fillMaxSize(),
              WindowChrome(bodyColor = 0x00000000, header = { state, chrome ->
                VrcodeWindowHeader(state, WindowStyle.MacOS, chrome.withTerminalTheme(theme, opacity))
              }),
              body = { ctx ->
                ScreenView(ctx, font).apply {
                  bodyColor = theme.background.toInt()
                  update(LayoutTree.Leaf(1), listOf(ScreenView.Pane(1, grid)), 1)
                  gridView(1)!!.appearance = AppSettings(terminalTheme = theme).gridAppearance()
                  backgroundOpacity = opacity
                  screen = this
                }
              })
          }
        }
      }
    }
    compose.waitForIdle()
    val room = compose.onNodeWithTag("room").fetchSemanticsNode().boundsInWindow
    val (sx, sy) = IntArray(2).also { screen.getLocationInWindow(it) }.let { (it[0] - room.left) to (it[1] - room.top) }
    val pane = screen.gridView(1)!!
    assertTrue("the body extends past the pane", sx + pane.width + 60 < room.width)
    val inPane = sx + pane.width - 4f to sy + pane.height - 4f
    val aroundPane = sx + pane.width + 40f to sy + 20f
    val titleBar = room.width * 0.28f to sy / 2f
    val appColored = sx + pane.paddingPx + font.cellWidth * 1.5f to sy + pane.paddingPx + font.cellHeight * 3.5f

    val half = capture(context, "window-opacity-50.png")
    val oneLayer = blend(theme.background, 0.5f)
    for ((name, point) in listOf("pane" to inPane, "body around the pane" to aroundPane, "title bar" to titleBar)) {
      assertColor("$name at 50%", oneLayer, half, point)
    }
    assertColor("app-colored cell stays solid", 0xFF00FF00, half, appColored)

    compose.runOnIdle { opacity = 1f; screen.backgroundOpacity = 1f }
    val solid = capture(context, "window-opacity-100.png")
    for ((name, point) in listOf("pane" to inPane, "body around the pane" to aroundPane)) {
      assertColor("$name at 100%", theme.background, solid, point)
    }
    assertColor("title bar at 100%", theme.background, solid, titleBar)
  }

  /** The app default and one window's own opacity are separate; Use app defaults drops the window's. */
  @Test fun appAndWindowOpacityAreIndependentAndSaved() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("window-opacity-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    val store = SettingsStore(preferences)
    var settings by mutableStateOf(store.load())
    var overrides by mutableStateOf(TerminalWindowSettings())
    var page by mutableStateOf(SettingsPage.Appearance)
    var showSheet by mutableStateOf(false)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(1000.dp, 760.dp)) {
          if (!showSheet) SettingsPanel(settings, page, ConnectStatus.Idle, emptyList(),
            customizationTab = CustomizationTab.Pointer, onCustomizationTab = {},
            onPage = { page = it }, onChange = { settings = it; store.save(it) },
            onArrange = {}, onConnect = {}, onDisconnect = {}, onClose = {})
          else TerminalWindowSettingsSheet("Terminal", overrides, settings, WindowMode.Follow,
            onChange = { overrides = it }, onMode = {}, onReset = { overrides = TerminalWindowSettings() }, onClose = {})
        }
      }
    }
    // Semi-transparent out of the box.
    compose.onNodeWithTag("settings-window-opacity-value").performScrollTo().assertTextEquals("80%")
    compose.onNodeWithTag("settings-window-opacity").performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { it(0.62f) }
    compose.onNodeWithTag("settings-window-opacity-value").assertTextEquals("60%")
    compose.runOnIdle { assertEquals(0.6f, SettingsStore(preferences).load().windowOpacity) }

    compose.runOnIdle { showSheet = true }
    compose.onNodeWithTag("window-settings-opacity-value").performScrollTo().assertTextEquals("60%")
    compose.onNodeWithTag("window-settings-opacity").performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
    compose.onNodeWithTag("window-settings-opacity-value").assertTextEquals("100%")
    compose.runOnIdle {
      assertEquals(1f, overrides.opacity)
      assertEquals(1f, overrides.resolve(settings).windowOpacity)
      // The window's choice does not touch the app default.
      assertEquals(0.6f, SettingsStore(preferences).load().windowOpacity)
    }
    compose.onNodeWithTag("window-settings-defaults").performScrollTo().performClick()
    compose.onNodeWithTag("window-settings-opacity-value").performScrollTo().assertTextEquals("60%")
    compose.runOnIdle { assertNull(overrides.opacity) }
  }

  /** [color] at [opacity] over pure red. */
  private fun blend(color: Long, opacity: Float): Long {
    fun channel(shift: Int, under: Int) = (((color shr shift) and 0xFF) * opacity + under * (1 - opacity)).toLong()
    return 0xFF000000 or (channel(16, 255) shl 16) or (channel(8, 0) shl 8) or channel(0, 0)
  }

  private fun assertColor(message: String, expected: Long, image: Bitmap, point: Pair<Float, Float>) {
    val actual = image.getPixel(point.first.toInt(), point.second.toInt())
    val close = listOf(16, 8, 0).all { shift -> abs(((expected shr shift) and 0xFF).toInt() - ((actual shr shift) and 0xFF)) <= 4 }
    assertTrue("%s: expected #%06X, got #%06X".format(message, expected and 0xFFFFFF, actual and 0xFFFFFF), close)
  }

  private fun capture(context: Context, name: String): Bitmap {
    val bitmap = compose.onNodeWithTag("room").captureToImage().asAndroidBitmap()
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: context.getExternalFilesDir(null)!!
    directory.mkdirs()
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return bitmap
  }
}
