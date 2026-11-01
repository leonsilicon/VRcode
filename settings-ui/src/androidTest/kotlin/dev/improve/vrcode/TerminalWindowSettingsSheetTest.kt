package dev.improve.vrcode

import android.graphics.Bitmap
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.xmux.window.frame.WindowFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalWindowSettingsSheetTest {
  @get:Rule val compose = createComposeRule()

  @Test fun bothHeaderStylesOpenOnlyTheirOwnWindowAndKeepOverridesAfterClosing() {
    var defaults by mutableStateOf(AppSettings())
    val overrides = mutableStateMapOf<String, TerminalWindowSettings>()
    val modes = mutableStateMapOf<String, WindowMode>()
    var open by mutableStateOf<String?>(null)
    var bodyCreations = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Row(Modifier.requiredSize(1000.dp, 800.dp)) {
          for (id in listOf("first", "second")) {
            val current = overrides[id] ?: TerminalWindowSettings()
            val appearance = current.resolve(defaults)
            Box(Modifier.weight(1f).fillMaxHeight().testTag(id)) {
              WindowFrame(remember { TerminalWindowState(id).apply { loading = false } }, Modifier.fillMaxSize(),
                WindowChrome(
                  trailingSlot = { WindowSettingsButton { open = if (open == id) null else id } },
                  header = { state, chrome -> VrcodeWindowHeader(state, appearance.windowStyle, chrome.withTerminalTheme(appearance.terminalTheme)) },
                  overlay = {
                    if (open == id) TerminalWindowSettingsSheet(id, current, defaults, modes[id] ?: WindowMode.Fixed,
                      onChange = { overrides[id] = it }, onMode = { modes[id] = it },
                      onReset = { overrides.remove(id); modes[id] = defaults.defaultWindowMode }, onClose = { open = null })
                  }),
                body = { context -> bodyCreations++; TextView(context).apply { text = "$id terminal" } })
            }
          }
        }
      }
    }
    fun gear(id: String) = compose.onNode(hasContentDescription("Open window settings") and hasAnyAncestor(hasTestTag(id)))
    for (style in WindowStyle.entries) {
      compose.runOnIdle { defaults = defaults.copy(windowStyle = style); overrides.clear(); modes.clear() }
      gear("first").performClick()
      compose.onNodeWithTag("vrcode-settings").assertDoesNotExist()
      compose.onNode(hasTestTag("window-settings-sheet") and hasAnyAncestor(hasTestTag("first"))).assertIsDisplayed()
      compose.onNode(hasTestTag("window-settings-sheet") and hasAnyAncestor(hasTestTag("second"))).assertDoesNotExist()
      compose.onNodeWithTag("window-settings-mode-follow").performScrollTo().performClick()
      compose.onNodeWithTag("window-settings-style-windows").performScrollTo().performClick()
      compose.onNodeWithTag("window-settings-theme-dracula").performScrollTo().performClick().assertIsSelected()
      compose.onNodeWithTag("window-settings-font-size").performScrollTo()
        .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
      compose.onNodeWithTag("window-settings-cursor-bar").performScrollTo().performClick()
      compose.runOnIdle {
        assertEquals(TerminalWindowSettings(WindowStyle.Windows, TerminalTheme.Dracula, 32f, TerminalCursor.Bar), overrides["first"])
        assertNull(overrides["second"])
        assertEquals(WindowMode.Follow, modes["first"])
        assertNull(modes["second"])
      }
      // Opening the other gear changes the owner, without opening app settings.
      gear("second").performClick()
      compose.onNodeWithTag("window-settings-theme-mac").performScrollTo().assertIsSelected()
      compose.onNodeWithTag("window-settings-font-value").performScrollTo().assertTextEquals("25 px")
      gear("first").performClick()
      compose.onNodeWithTag("window-settings-theme-dracula").performScrollTo().assertIsSelected()
      compose.onNodeWithTag("window-settings-close").performClick()
      compose.onNodeWithTag("window-settings-sheet").assertDoesNotExist()
      gear("first").performClick()
      compose.onNodeWithTag("window-settings-cursor-bar").performScrollTo().assertIsSelected()
      compose.onNodeWithTag("window-settings-defaults").performScrollTo().performClick()
      compose.runOnIdle { assertNull(overrides["first"]); assertEquals(defaults.defaultWindowMode, modes["first"]); assertEquals(2, bodyCreations) }
      compose.onNodeWithTag("window-settings-close").performClick()
    }
  }

  @Test fun shortTerminalFillsItsViewportAndScrollsWithCloseAlwaysVisible() {
    var open by mutableStateOf(true)
    var bodyClicks = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(800.dp, 320.dp).testTag("window-settings-test-window")) {
          WindowFrame(remember { TerminalWindowState("Short terminal").apply { loading = false } }, Modifier.fillMaxSize(),
            WindowChrome(overlay = {
              if (open) TerminalWindowSettingsSheet("Short terminal", TerminalWindowSettings(), AppSettings(), WindowMode.Fixed,
                onChange = {}, onMode = {}, onReset = {}, onClose = { open = false })
            }), body = { context -> TextView(context).apply { text = "Terminal"; setOnClickListener { bodyClicks++ } } })
        }
      }
    }
    val body = compose.onNodeWithTag("xmux-grid").fetchSemanticsNode().boundsInRoot
    val sheet = compose.onNodeWithTag("window-settings-sheet").fetchSemanticsNode().boundsInRoot
    assertEquals(body.top, sheet.top, 1f)
    assertEquals(body.bottom, sheet.bottom, 1f)
    val close = compose.onNodeWithTag("window-settings-close").fetchSemanticsNode().boundsInRoot
    compose.onNodeWithTag("window-settings-sheet").performTouchInput { click(Offset(5f, 5f)) }
    compose.onNodeWithTag("window-settings-sheet").assertIsDisplayed()
    capture("window-settings-short-top.png")
    compose.onNodeWithTag("window-settings-defaults").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("window-settings-close").assertIsDisplayed()
    assertEquals(close, compose.onNodeWithTag("window-settings-close").fetchSemanticsNode().boundsInRoot)
    capture("window-settings-short-scrolled.png")
    compose.runOnIdle { assertEquals(0, bodyClicks) }
    compose.onNodeWithTag("window-settings-close").performClick()
    compose.onNodeWithTag("window-settings-overlay").assertDoesNotExist()
  }

  @Test fun tallTerminalCapsTopSheetAndScrimDismissesWithoutRecreatingTerminal() {
    var open by mutableStateOf(true)
    var creations = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(800.dp, 800.dp).testTag("window-settings-test-window")) {
          WindowFrame(remember { TerminalWindowState("Tall terminal").apply { loading = false } }, Modifier.fillMaxSize(),
            WindowChrome(overlay = {
              if (open) TerminalWindowSettingsSheet("Tall terminal", TerminalWindowSettings(), AppSettings(), WindowMode.Fixed,
                onChange = {}, onMode = {}, onReset = {}, onClose = { open = false })
            }).withTerminalTheme(TerminalTheme.Dracula),
            body = { context -> creations++; TextView(context).apply { text = "Terminal" } })
        }
      }
    }
    val body = compose.onNodeWithTag("xmux-grid").fetchSemanticsNode().boundsInRoot
    val sheet = compose.onNodeWithTag("window-settings-sheet").fetchSemanticsNode().boundsInRoot
    assertEquals(body.top, sheet.top, 1f)
    assertTrue(sheet.height <= 680f)
    assertTrue(sheet.bottom < body.bottom)
    capture("window-settings-tall.png")
    compose.onNodeWithTag("window-settings-scrim").performTouchInput { click(Offset(center.x, height - 10f)) }
    compose.onNodeWithTag("window-settings-sheet").assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, creations) }
  }

  private fun capture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: instrumentation.targetContext.getExternalFilesDir(null)!!
    directory.mkdirs()
    File(directory, name).outputStream().use {
      compose.onNodeWithTag("window-settings-test-window").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
