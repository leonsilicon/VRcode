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

class PointerCursorSettingsTest {
  @get:Rule val compose = createComposeRule()

  @Test fun pointerStyleAndSizeApplyPersistReloadAndRestoreDefaults() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("pointer-settings-test", Context.MODE_PRIVATE)
    preferences.edit().clear().putString("pointer_cursor", "unknown")
      .putFloat("pointer_cursor_size", Float.NaN).commit()
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
    compose.onNodeWithTag("settings-pointer-arrow").assertIsSelected()
    compose.onNodeWithTag("settings-pointer-size-value").assertTextEquals("100%")
    compose.onNodeWithTag("settings-pointer-size").performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
    compose.onNodeWithTag("settings-pointer-size-value").assertTextEquals("150%")
    for (style in PointerCursorStyle.entries) {
      compose.onNodeWithTag("settings-pointer-${style.storageValue}").performScrollTo().performClick().assertIsSelected()
      for (other in PointerCursorStyle.entries.filter { it != style }) {
        compose.onNodeWithTag("settings-pointer-${other.storageValue}").assertIsNotSelected()
      }
      compose.runOnIdle {
        val saved = SettingsStore(preferences).load()
        assertEquals(style, saved.pointerCursor)
        assertEquals(1.5f, saved.pointerCursorSize)
        assertEquals(TerminalCursor.Terminal, saved.terminalCursor)
        assertEquals(VoiceProp.Microphone, saved.textMicProp)
        assertEquals(VoiceProp.NewsMicrophone, saved.aiMicProp)
      }
    }
    compose.onNodeWithTag("settings-pointer-size").assertDoesNotExist()
    compose.runOnIdle { generation++ }
    compose.onNodeWithTag("settings-pointer-none").assertIsSelected()
    compose.onNodeWithTag("settings-pointer-size").assertDoesNotExist()
    val bitmap = compose.onNodeWithTag("vrcode-settings").captureToImage().asAndroidBitmap()
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: context.getExternalFilesDir(null)!!
    directory.mkdirs()
    File(directory, "settings-pointer-cursor.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    compose.onNodeWithTag("settings-pointer-dot").performClick()
    compose.onNodeWithTag("settings-pointer-size-value").assertTextEquals("150%")
    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-defaults").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(AppSettings(), SettingsStore(preferences).load()) }
    compose.onNodeWithTag("settings-page-Customization").performClick()
    compose.onNodeWithTag("settings-pointer-arrow").performScrollTo().assertIsSelected()
    compose.onNodeWithTag("settings-pointer-size-value").assertTextEquals("100%")
    preferences.edit().clear().commit()
  }
}
