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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.vrcode.QuickInputSlot.*
import dev.improve.xmux.connect.panel.ConnectStatus
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuickInputSettingsTest {
  @get:Rule val compose = createComposeRule()

  private fun slot(slot: QuickInputSlot) = compose.onNodeWithTag("quick-input-slot-${slot.name}")
  private fun option(input: QuickInput) = compose.onNodeWithTag("quick-input-option-${input.storageValue}").performScrollTo()

  @Test fun quickInputsEditSaveReloadAndRestoreDefaults() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("quick-input-settings-test", Context.MODE_PRIVATE)
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
    fun saved() = SettingsStore(preferences).load().quickInputs

    compose.onNodeWithTag("settings-customization-tab-QuickInputs").performClick()
    // The default ring: Esc, Ctrl-C, Tab and Enter on the diagonals, the rest empty.
    slot(TopLeft).assertContentDescriptionEquals("Top left: Esc")
    slot(TopRight).assertContentDescriptionEquals("Top right: Ctrl-C").assertIsSelected()
    slot(BottomLeft).assertContentDescriptionEquals("Bottom left: Tab")
    slot(BottomRight).assertContentDescriptionEquals("Bottom right: Enter")
    for (empty in listOf(Top, Right, Bottom, Left)) slot(empty).assertContentDescriptionEquals("${empty.label}: Empty")
    option(QuickInput.CtrlC).assertIsSelected()
    capture("settings-quick-inputs.png")

    // Fill an empty direction.
    slot(Top).performClick().assertIsSelected()
    option(QuickInput.Up).performClick().assertIsSelected()
    compose.runOnIdle { assertEquals(QuickInput.Up, saved()[Top]) }
    slot(Top).assertContentDescriptionEquals("Top: Up")

    // A key already on the ring swaps with the chosen direction's.
    slot(TopRight).performScrollTo().performClick()
    option(QuickInput.Enter).performClick()
    compose.runOnIdle {
      assertEquals(QuickInput.Enter, saved()[TopRight])
      assertEquals(QuickInput.CtrlC, saved()[BottomRight])
    }

    // Leave empty, then a new key into the emptied direction.
    slot(TopLeft).performScrollTo().performClick()
    compose.onNodeWithTag("quick-input-clear").performClick().assertIsNotEnabled()
    compose.runOnIdle { assertNull(saved()[TopLeft]) }
    option(QuickInput.ShiftTab).performClick()
    compose.runOnIdle { assertEquals(QuickInput.ShiftTab, saved()[TopLeft]) }

    // Reloading shows what was saved.
    val expected = QuickInputRing.ring(Top to QuickInput.Up, TopRight to QuickInput.Enter, BottomRight to QuickInput.CtrlC,
      BottomLeft to QuickInput.Tab, TopLeft to QuickInput.ShiftTab)
    compose.runOnIdle { assertEquals(expected, saved()); generation++ }
    compose.onNodeWithTag("settings-customization-tab-QuickInputs").performClick()
    slot(TopRight).assertContentDescriptionEquals("Top right: Enter")
    slot(TopLeft).assertContentDescriptionEquals("Top left: Shift-Tab")
    capture("settings-quick-inputs-custom.png")

    compose.onNodeWithTag("quick-inputs-defaults").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(QuickInputRing.Default, saved()) }
    slot(TopRight).performScrollTo().assertContentDescriptionEquals("Top right: Ctrl-C")

    // Restore default settings resets the ring too.
    slot(Top).performClick()
    option(QuickInput.Keyboard).performClick()
    compose.onNodeWithTag("settings-page-Workspace").performClick()
    compose.onNodeWithTag("settings-defaults").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(QuickInputRing.Default, saved()) }
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: File(context.getExternalFilesDir(null), "screenshots")
    directory.mkdirs()
    val bitmap = compose.onNodeWithTag("vrcode-settings").captureToImage().asAndroidBitmap()
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
