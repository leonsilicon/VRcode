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

class TerminalAppearanceSettingsTest {
  @get:Rule val compose = createComposeRule()

  @Test fun themesTextAndCursorPersistAcrossRecreationAndResetIndependently() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("terminal-appearance-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    val store = SettingsStore(preferences)
    store.save(AppSettings(passthrough = false, windowStyle = WindowStyle.Windows))
    var generation by mutableStateOf(0)
    compose.setContent {
      key(generation) {
        var settings by remember { mutableStateOf(SettingsStore(preferences).load()) }
        var page by remember { mutableStateOf(SettingsPage.Appearance) }
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          Box(Modifier.requiredSize(1000.dp, 760.dp)) {
            SettingsPanel(settings, page, ConnectStatus.Idle, emptyList(),
              customizationTab = CustomizationTab.Pointer, onCustomizationTab = {},
              onPage = { page = it }, onChange = { settings = it; store.save(it) },
              onArrange = {}, onConnect = {}, onDisconnect = {}, onClose = {})
          }
        }
      }
    }
    for (theme in TerminalTheme.entries) {
      compose.onNodeWithTag("settings-terminal-theme-${theme.storageValue}")
        .performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
      compose.runOnIdle { assertEquals(theme, SettingsStore(preferences).load().terminalTheme) }
    }
    compose.onNodeWithTag("settings-font-size").performScrollTo()
      .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
    compose.onNodeWithTag("settings-cursor-bar").performScrollTo().performClick().assertIsSelected()
    compose.runOnIdle {
      assertEquals(32f, SettingsStore(preferences).load().terminalFontSize)
      assertEquals(TerminalCursor.Bar, SettingsStore(preferences).load().terminalCursor)
      generation++
    }
    compose.onNodeWithTag("settings-terminal-theme-solarized-light").performScrollTo().assertIsSelected()
    compose.onNodeWithTag("settings-font-size-value").performScrollTo().assertTextEquals("32 px")
    compose.onNodeWithTag("settings-cursor-bar").performScrollTo().assertIsSelected()
    compose.onNodeWithTag("settings-terminal-preview").performScrollTo()
    capture(context, "terminal-appearance-light.png")

    compose.onNodeWithTag("settings-terminal-theme-catppuccin-mocha").performScrollTo().performClick()
    compose.onNodeWithTag("settings-terminal-preview").performScrollTo()
    capture(context, "terminal-appearance-dark.png")
    compose.onNodeWithTag("settings-terminal-defaults").performScrollTo().performClick()
    compose.runOnIdle {
      val restored = SettingsStore(preferences).load()
      assertEquals(TerminalTheme.Mac, restored.terminalTheme)
      assertEquals(AppSettings.DEFAULT_FONT_SIZE, restored.terminalFontSize)
      assertEquals(TerminalCursor.Terminal, restored.terminalCursor)
      assertEquals(WindowStyle.Windows, restored.windowStyle)
      assertFalse(restored.passthrough)
    }
    preferences.edit().clear().commit()
  }

  @Test fun unknownSavedAppearanceFallsBackAndInvalidSizeIsClamped() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("terminal-appearance-migration-test", Context.MODE_PRIVATE)
    preferences.edit().clear().putString("terminal_theme", "deleted").putString("terminal_cursor", "deleted")
      .putFloat("terminal_font_size", Float.NaN).commit()
    assertEquals(AppSettings(), SettingsStore(preferences).load())
    preferences.edit().clear().commit()
  }

  private fun capture(context: Context, name: String) {
    val bitmap = compose.onNodeWithTag("vrcode-settings").captureToImage().asAndroidBitmap()
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: context.getExternalFilesDir(null)!!
    directory.mkdirs()
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
