package dev.improve.vrcode

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.connect.panel.ConnectStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalSizeSettingsTest {
  @get:Rule val compose = createComposeRule()

  @Test fun missingAndInvalidSavedSizesUseDefaultsAndSafeBounds() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("terminal-size-bounds-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    val store = SettingsStore(preferences)
    assertEquals(TerminalSize(120, 36), store.load().defaultTerminalSize)
    preferences.edit().putInt("default_terminal_cols", 0).putInt("default_terminal_rows", Int.MAX_VALUE).commit()
    assertEquals(TerminalSize(40, 72), store.load().defaultTerminalSize)
    store.save(AppSettings(defaultTerminalSize = TerminalSize(Int.MAX_VALUE, -1)))
    assertEquals(TerminalSize(200, 12), store.load().defaultTerminalSize)
    preferences.edit().clear().commit()
  }

  @Test fun presetsAndCustomDimensionsPersistReloadAndRestore() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("terminal-size-settings-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    var generation by mutableStateOf(0)
    compose.setContent {
      key(generation) {
        val store = remember { SettingsStore(preferences) }
        var settings by remember { mutableStateOf(store.load()) }
        var page by remember { mutableStateOf(SettingsPage.Workspace) }
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          Box(Modifier.requiredSize(1000.dp, 760.dp)) {
            SettingsPanel(settings, page, ConnectStatus.Idle, emptyList(),
              onPage = { page = it }, onChange = { settings = it; store.save(it) },
              customizationTab = CustomizationTab.Pointer, onCustomizationTab = {},
              onArrange = {}, onConnect = {}, onDisconnect = {}, onClose = {})
          }
        }
      }
    }
    compose.onNodeWithTag("settings-terminal-size-Standard").assertIsSelected()
    for (preset in TerminalSizePreset.entries) {
      compose.onNodeWithTag("settings-terminal-size-${preset.name}").performScrollTo().performClick().assertIsSelected()
      for (other in TerminalSizePreset.entries.filter { it != preset }) {
        compose.onNodeWithTag("settings-terminal-size-${other.name}").assertIsNotSelected()
      }
      compose.runOnIdle { assertEquals(preset.size, SettingsStore(preferences).load().defaultTerminalSize) }
    }
    compose.onNodeWithTag("settings-terminal-columns").performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { it(137f) }
    compose.runOnIdle { assertEquals(TerminalSize(137, 48), SettingsStore(preferences).load().defaultTerminalSize) }
    compose.onNodeWithTag("settings-terminal-rows").performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { it(41f) }
    compose.onNodeWithTag("settings-terminal-columns-increase").performScrollTo().performClick()
    compose.onNodeWithTag("settings-terminal-rows-decrease").performScrollTo().performClick()
    compose.onNodeWithTag("settings-terminal-size-value").assertTextEquals("Custom · 138 × 40")
    compose.runOnIdle {
      assertEquals(TerminalSize(138, 40), SettingsStore(preferences).load().defaultTerminalSize)
      assertEquals(WindowMode.Follow, SettingsStore(preferences).load().defaultWindowMode)
    }
    compose.onNodeWithTag("settings-page-Appearance").performClick()
    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-terminal-size-value").assertTextEquals("Custom · 138 × 40")
    compose.runOnIdle { generation++ }
    compose.onNodeWithTag("settings-terminal-size-value").assertTextEquals("Custom · 138 × 40")
    compose.onNodeWithTag("settings-terminal-size-Standard").performScrollTo().performClick()
    compose.onNodeWithTag("settings-terminal-rows-increase").performScrollTo()
    val directory = context.getExternalFilesDir(null)!!
    File(directory, "settings-terminal-size.png").outputStream().use {
      compose.onNodeWithTag("vrcode-settings").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    compose.onNodeWithTag("settings-terminal-columns").performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
    compose.onNodeWithTag("settings-terminal-columns-increase").assertIsNotEnabled()
    compose.onNodeWithTag("settings-terminal-rows").performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
    compose.onNodeWithTag("settings-terminal-rows-decrease").assertIsNotEnabled()
    compose.onNodeWithTag("settings-defaults").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(AppSettings(), SettingsStore(preferences).load()) }
    compose.onNodeWithTag("settings-terminal-size-Standard").performScrollTo().assertIsSelected()
    preferences.edit().clear().commit()
  }
}
