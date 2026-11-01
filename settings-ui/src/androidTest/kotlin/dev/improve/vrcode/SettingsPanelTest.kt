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

  @Test fun micPreferencesMigrateAndRejectUnknownPropsPerMode() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("mic-migration-test", Context.MODE_PRIVATE)
    val store = SettingsStore(preferences)
    for (legacy in listOf(null, "microphone", "boombox", "banana", "removed-prop")) {
      preferences.edit().clear().putString("voice_prop", legacy).commit()
      val settings = store.load()
      assertEquals(VoiceProp.Microphone, settings.textMicProp)
      assertEquals(if (legacy == "banana") VoiceProp.Banana else VoiceProp.NewsMicrophone, settings.aiMicProp)
    }
    preferences.edit().clear().putString("voice_prop", "banana")
      .putString("text_mic_prop", "removed-prop").putString("ai_mic_prop", "removed-prop").commit()
    assertEquals(VoiceProp.Microphone, store.load().textMicProp)
    assertEquals(VoiceProp.NewsMicrophone, store.load().aiMicProp)
    store.save(AppSettings(textMicProp = VoiceProp.Megaphone, aiMicProp = VoiceProp.Microphone))
    assertFalse(preferences.contains("voice_prop"))
    assertEquals(VoiceProp.Megaphone, store.load().textMicProp)
    assertEquals(VoiceProp.Microphone, store.load().aiMicProp)
    preferences.edit().clear().commit()
  }

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
    compose.onNodeWithTag("settings-customization-tab-TextMic").performClick().assertIsSelected()
    compose.onNodeWithTag("settings-voice-prop-microphone").assertIsSelected()
    compose.onNodeWithText("Text mic · Right hand").assertIsDisplayed()
    compose.onNodeWithTag("settings-customization-tab-AiMic").performClick().assertIsSelected()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").assertIsSelected()
    compose.onNodeWithText("AI mic · Left hand").assertIsDisplayed()
    for (mic in VoiceMic.entries) {
      val tab = if (mic == VoiceMic.Text) "TextMic" else "AiMic"
      compose.onNodeWithTag("settings-customization-tab-$tab").performClick()
      for (prop in VoiceProp.entries) {
        compose.onNodeWithTag("settings-voice-prop-${prop.storageValue}")
          .performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(prop, mic.prop(SettingsStore(preferences).load())) }
        for (other in VoiceProp.entries.filter { it != prop }) {
          compose.onNodeWithTag("settings-voice-prop-${other.storageValue}").assertIsNotSelected()
        }
      }
    }
    // Choose different props and verify independence across tabs, pages and recreation.
    compose.onNodeWithTag("settings-voice-prop-news-microphone").performScrollTo().performClick()
    compose.onNodeWithTag("settings-customization-tab-TextMic").performClick()
    compose.onNodeWithTag("settings-voice-prop-banana").performScrollTo().assertIsSelected()
    compose.onNodeWithTag("settings-voice-prop-megaphone").performScrollTo().performClick()
    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-customization-tabs").assertDoesNotExist()
    compose.onNodeWithTag("settings-page-Customization").performClick()
    compose.onNodeWithTag("settings-customization-tab-TextMic").assertIsSelected()
    compose.onNodeWithTag("settings-voice-prop-megaphone").performScrollTo().assertIsSelected()
    compose.runOnIdle { generation++ }
    compose.onNodeWithTag("settings-customization-tab-TextMic").performClick()
    compose.onNodeWithTag("settings-voice-prop-megaphone").performScrollTo().assertIsSelected()
    capture(context, "settings-text-mic.png")
    compose.onNodeWithTag("settings-ai-provider-claude").assertDoesNotExist() // only the AI mic has a provider
    compose.onNodeWithTag("settings-customization-tab-AiMic").assertIsDisplayed().performClick()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").assertIsSelected()
    // The AI provider defaults to Claude; each choice saves and is exclusive.
    compose.onNodeWithTag("settings-ai-provider-claude").performScrollTo().assertIsSelected()
    for (provider in AiProvider.entries.reversed()) {
      compose.onNodeWithTag("settings-ai-provider-${provider.storageValue}").performScrollTo().performClick().assertIsSelected()
      compose.runOnIdle { assertEquals(provider, SettingsStore(preferences).load().aiProvider) }
      for (other in AiProvider.entries.filter { it != provider }) {
        compose.onNodeWithTag("settings-ai-provider-${other.storageValue}").assertIsNotSelected()
      }
    }
    compose.onNodeWithTag("settings-ai-provider-gemini").performScrollTo().performClick()
    compose.onNodeWithText("Your Mac sends what you say to Gemini", substring = true).assertExists()
    capture(context, "settings-ai-provider-gemini.png")
    compose.onNodeWithTag("settings-ai-provider-claude").performScrollTo().performClick()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").performScrollTo()
    capture(context, "settings-customization.png")
    compose.onNodeWithTag("settings-customization-tab-Pointer").performClick()
    compose.onNodeWithTag("settings-pointer-arrow").assertIsDisplayed()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").assertDoesNotExist()
    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-defaults").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(VoiceProp.Microphone, SettingsStore(preferences).load().textMicProp)
      assertEquals(VoiceProp.NewsMicrophone, SettingsStore(preferences).load().aiMicProp)
    }
    compose.onNodeWithTag("settings-page-Customization").performClick()
    compose.onNodeWithTag("settings-customization-tab-TextMic").performClick()
    compose.onNodeWithTag("settings-voice-prop-microphone").assertIsSelected()
    compose.onNodeWithTag("settings-customization-tab-AiMic").performClick()
    compose.onNodeWithTag("settings-voice-prop-news-microphone").assertIsSelected()
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
