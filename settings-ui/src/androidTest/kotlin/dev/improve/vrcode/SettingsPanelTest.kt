package dev.improve.vrcode

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.connect.panel.ConnectStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the same Compose panel used in VR without requiring a tracked hand for automation. */
class SettingsPanelTest {
  @get:Rule val compose = createComposeRule()

  @Test fun newWindowModeSelectsPersistsReloadsAndRestores() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("window-mode-settings-test", Context.MODE_PRIVATE)
    preferences.edit().clear().putString("default_window_mode", "unknown").commit()
    var generation by mutableStateOf(0)
    compose.setContent {
      key(generation) {
        val store = remember { SettingsStore(preferences) }
        var settings by remember { mutableStateOf(store.load()) }
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          Box(Modifier.requiredSize(1000.dp, 760.dp)) {
            SettingsPanel(settings, SettingsPage.Workspace, ConnectStatus.Idle, emptyList(),
              onPage = {}, onChange = { settings = it; store.save(it) },
              customizationTab = CustomizationTab.Pointer, onCustomizationTab = {},
              onArrange = {}, onConnect = {}, onDisconnect = {}, onClose = {})
          }
        }
      }
    }
    compose.onNodeWithTag("settings-window-mode-fixed").assertIsSelected()
    compose.onNodeWithTag("settings-window-mode-follow").performScrollTo().performClick().assertIsSelected()
    compose.onNodeWithTag("settings-window-mode-fixed").assertIsNotSelected()
    compose.runOnIdle { assertEquals(WindowMode.Follow, SettingsStore(preferences).load().defaultWindowMode); generation++ }
    compose.onNodeWithTag("settings-window-mode-follow").assertIsSelected()
    capture(context, "settings-window-mode.png")
    compose.onNodeWithTag("settings-defaults").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(WindowMode.Fixed, SettingsStore(preferences).load().defaultWindowMode) }
    compose.onNodeWithTag("settings-window-mode-fixed").performScrollTo().assertIsSelected()
    preferences.edit().clear().commit()
  }

  @Test fun voicePropsAppearSelectPersistAndRestore() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("voice-prop-settings-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    var generation by mutableStateOf(0)
    compose.setContent {
      key(generation) {
        val store = remember { SettingsStore(preferences) }
        var settings by remember { mutableStateOf(store.load()) }
        var page by remember { mutableStateOf(SettingsPage.Customization) }
        var tab by remember { mutableStateOf(CustomizationTab.Pointer) }
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
          Box(Modifier.requiredSize(1000.dp, 760.dp)) {
            SettingsPanel(settings, page, ConnectStatus.Idle, emptyList(),
              onPage = { page = it }, onChange = { settings = it; store.save(it) },
              customizationTab = tab, onCustomizationTab = { tab = it },
              onArrange = {}, onConnect = {}, onDisconnect = {}, onClose = {})
          }
        }
      }
    }
    compose.onNodeWithTag("settings-customization-tab-Pointer").assertIsSelected()
    compose.onNodeWithTag("settings-pointer-arrow").assertIsDisplayed()
    compose.onNodeWithTag("settings-voice-prop-microphone").assertDoesNotExist()
    compose.onNodeWithTag("settings-customization-tab-Microphone").performClick().assertIsSelected()
    compose.onNodeWithTag("settings-pointer-arrow").assertDoesNotExist()
    for (prop in VoiceProp.entries) {
      compose.onNodeWithTag("settings-voice-prop-${prop.storageValue}")
        .performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
      compose.runOnIdle { assertEquals(prop, SettingsStore(preferences).load().voiceProp) }
      for (other in VoiceProp.entries.filter { it != prop }) {
        compose.onNodeWithTag("settings-voice-prop-${other.storageValue}").assertIsNotSelected()
      }
    }
    compose.onNodeWithTag("settings-voice-prop-news-microphone").performScrollTo().performClick()
    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-customization-tabs").assertDoesNotExist()
    compose.onNodeWithTag("settings-page-Customization").performClick()
    compose.onNodeWithTag("settings-customization-tab-Microphone").assertIsSelected()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").assertIsSelected()
    // Recreate all remembered UI state and reload from a fresh SettingsStore.
    compose.runOnIdle { generation++ }
    compose.onNodeWithTag("settings-customization-tab-Microphone").performClick()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").assertIsSelected()
    compose.onNodeWithTag("settings-voice-prop-banana").performScrollTo()
    capture(context, "settings-customization.png")
    // The tab bar stays reachable after scrolling the content. Switching resets the new view.
    compose.onNodeWithTag("settings-customization-tab-Pointer").assertIsDisplayed().performClick()
    compose.onNodeWithTag("settings-pointer-arrow").assertIsDisplayed()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").assertDoesNotExist()
    compose.onNodeWithTag("settings-customization-tab-Microphone").performClick()
    compose.onAllNodesWithText("Microphone").assertCountEquals(2)
    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-defaults").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(VoiceProp.Microphone, SettingsStore(preferences).load().voiceProp) }
    compose.onNodeWithTag("settings-page-Customization").performClick()
    compose.onNodeWithTag("settings-voice-prop-microphone").assertIsSelected()
    preferences.edit().clear().commit()
  }

  @Test fun settingsApplyPersistNavigateAndClose() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("settings-ui-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    val store = SettingsStore(preferences)
    var closed = false
    var arranged = false
    compose.setContent {
      var settings by remember { mutableStateOf(store.load()) }
      var page by remember { mutableStateOf(SettingsPage.Workspace) }
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(1000.dp, 760.dp)) {
          SettingsPanel(settings, page, ConnectStatus.Idle, emptyList(),
            customizationTab = CustomizationTab.Pointer, onCustomizationTab = {},
            onPage = { page = it },
            onChange = { settings = it; store.save(it) },
            onArrange = { arranged = true }, onConnect = {}, onDisconnect = {}, onClose = { closed = true })
        }
      }
    }
    compose.onNodeWithContentDescription("Passthrough").assertIsOn()
    capture(context, "settings-workspace.png")
    compose.onNodeWithContentDescription("Passthrough").performClick().assertIsOff()
    compose.runOnIdle {
      val reloaded = SettingsStore(preferences).load()
      assertFalse(reloaded.passthrough)
    }
    compose.onNodeWithTag("settings-arrange").performScrollTo().performClick()
    compose.runOnIdle { assertTrue(arranged) }

    compose.onNodeWithTag("settings-page-Connection").performClick()
    compose.onNodeWithContentDescription("Connect on launch").performClick().assertIsOff()
    compose.onNodeWithText("Connect to the xmux bridge on your Mac").assertIsDisplayed()
    compose.runOnIdle { assertFalse(SettingsStore(preferences).load().autoConnect) }
    capture(context, "settings-connection.png")

    compose.onNodeWithTag("settings-page-Appearance").performClick()
    compose.onNodeWithTag("settings-window-style-macos").assertIsSelected()
    compose.onNodeWithTag("settings-window-style-windows").assertIsEnabled().performClick().assertIsSelected()
    compose.onNodeWithTag("settings-window-style-macos").assertIsNotSelected()
    compose.runOnIdle { assertEquals(WindowStyle.Windows, SettingsStore(preferences).load().windowStyle) }
    capture(context, "settings-appearance.png")

    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-defaults").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(AppSettings(), SettingsStore(preferences).load()) }
    compose.onNodeWithTag("settings-close").performClick()
    compose.runOnIdle { assertTrue(closed) }
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
