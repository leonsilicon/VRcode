package dev.improve.vrcode.connection

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ConnectionPanelTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val scope = MainScope()
  private lateinit var controller: ConnectionController

  @Before fun content() {
    compose.runOnUiThread {
      controller = ConnectionController(compose.activity, scope, { _, _ -> }, { })
      controller.update(ConnectionProfile())
    }
    compose.setContent {
      MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ConnectionPanel(controller) } }
    }
  }

  @After fun close() { compose.runOnUiThread { controller.close(); scope.cancel() } }

  @Test fun defaultsToAutomaticAndRequiresPairing() {
    compose.onNodeWithTag("connection-mode").assertTextContains("Automatic (recommended)")
    compose.onNodeWithTag("connection-connect").assertIsNotEnabled()
    compose.onNodeWithTag("connection-disconnect").performScrollTo().assertIsEnabled()
  }

  @Test fun switchingModePersistsAndShowsRealSetup() {
    compose.onNodeWithTag("connection-mode").performClick()
    compose.onNodeWithTag("connection-mode-WiFiDirect").performScrollTo().performClick()
    compose.onNodeWithTag("connection-create-direct").performScrollTo().assertIsDisplayed()
    compose.runOnIdle { assertEquals(ConnectionMode.WiFiDirect, ConnectionStore(compose.activity).load().mode) }
  }

  @Test fun everyTransportIsSelectable() {
    for (mode in ConnectionMode.entries) {
      compose.onNodeWithTag("connection-mode").performScrollTo().performClick()
      compose.onNodeWithTag("connection-mode-${mode.name}").performScrollTo().performClick()
      compose.runOnIdle { assertEquals(mode, controller.profile.value.mode) }
    }
    compose.onNodeWithTag("connection-mode").performScrollTo()
    val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
    File(directory, "connection-selector.png").outputStream().use {
      compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  @Test fun invalidPairingDoesNotEnableConnect() {
    compose.onNodeWithTag("connection-import").performTextInput("vrcode:not-valid")
    compose.onNodeWithTag("connection-save-mac").performClick()
    compose.onNodeWithTag("connection-connect").assertIsNotEnabled()
  }
}
