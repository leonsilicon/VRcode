package dev.improve.vrcode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.content.ContentValues
import android.provider.MediaStore

class CmuxActionsPanelTest {
  @get:Rule val compose = createComposeRule()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val pane = "pane_" + "1".repeat(32)
  private val terminal = "term_" + "2".repeat(32)

  @Test fun theWholePublicCatalogHasFormsAndKeepsExplicitTargetsAndExactNames() {
    val catalog = CmuxCatalog(context)
    assertEquals(127, catalog.operations.size)
    assertEquals(69, catalog.operations.count { it.mutation })
    assertEquals(5, catalog.operations.count { it.kind == "stream_open" })
    val split = catalog.operation("pane.split")
    val defaults = catalog.defaults(split, mapOf("pane" to pane, "terminal" to terminal, "screen" to "screen_old"))
    assertEquals(mapOf("pane" to pane), defaults)
    val args = catalog.parameters(split, defaults + ("direction" to "right"))
    assertEquals(pane, args.getString("pane"))
    assertFalse(args.has("screen"))
    val rename = catalog.operation("tab.rename")
    val value = catalog.parameters(rename, mapOf("tab" to "tab_target", "name" to "  café\nQuest  "))
    assertEquals("  café\nQuest  ", value.getString("name"))
    assertEquals("", catalog.parameters(rename, mapOf("tab" to "tab_target", "name" to "")).getString("name"))
    assertTrue(catalog.parameters(rename, mapOf("tab" to "tab_target"), setOf("name")).isNull("name"))
    assertEquals("", catalog.defaults(rename, mapOf("tab" to "tab_target")).getValue("name"))
    val run = catalog.operation("pane.run")
    assertThrows(IllegalArgumentException::class.java) { catalog.parameters(run, mapOf("pane" to pane, "argv" to "[\"/bin/sh\"] extra")) }
    assertThrows(IllegalArgumentException::class.java) { catalog.parameters(split, defaults) }
    assertEquals(0, catalog.defaults(catalog.operation("workspace.create"), mapOf("pane" to pane)).size)
  }

  @Test fun splitFormSendsTheQuestTargetAndDoesNotBorrowMacFocus() {
    var operation = ""
    var params: JSONObject? = null
    panel(onRun = { name, values -> operation = name; params = values })
    screenshot("cmux-actions-common.png")
    compose.onNodeWithTag("cmux-action-search").performTextInput("pane split")
    compose.onNodeWithTag("cmux-action-pane.split").performClick()
    compose.onNodeWithText("Choose Direction").performClick()
    compose.onNodeWithText("right", useUnmergedTree = true).performClick()
    screenshot("cmux-actions-split.png")
    compose.onNodeWithTag("cmux-run").performClick()
    compose.runOnIdle {
      assertEquals("pane.split", operation)
      assertEquals(pane, params!!.getString("pane"))
      assertEquals("right", params!!.getString("direction"))
      assertFalse(params!!.has("screen"))
      assertFalse(params!!.has("terminal"))
    }
  }

  @Test fun allActionsIncludesBrowserAndStreamControlsAndOfflineCannotRun() {
    var called = false
    panel(connected = false, onRun = { _, _ -> called = true })
    compose.onNodeWithText("All 127 actions").performClick()
    compose.onNodeWithTag("cmux-action-search").performTextInput("browser navigate")
    compose.onNodeWithTag("cmux-action-browser.navigate").performClick()
    compose.onNodeWithTag("cmux-field-browser").assertExists()
    compose.onNodeWithTag("cmux-run").assertIsNotEnabled()
    compose.runOnIdle { assertFalse(called) }
  }

  @Test fun textSelectionAndSearchAreOwnedByTheQuest() {
    val text = "first line\nQUEST_BACKGROUND_INPUT\nlast line"
    panel(text = text)
    compose.onNodeWithTag("cmux-terminal-text").assertTextContains(text)
    compose.onNodeWithText("Find in terminal text").performTextInput("BACKGROUND")
    compose.onNodeWithText("Find next").performClick()
    val start = text.indexOf("BACKGROUND")
    compose.onNodeWithTag("cmux-terminal-text").assert(
      SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, TextRange(start, start + "BACKGROUND".length)))
  }

  private fun panel(connected: Boolean = true, text: String? = null, onRun: (String, JSONObject) -> Unit = { _, _ -> }) {
    val catalog = CmuxCatalog(context)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(1100.dp, 850.dp)) {
          CmuxActionsPanel(catalog, mapOf("pane" to pane, "terminal" to terminal),
            listOf(CmuxResource("pane", pane, "pane · Quest pane"), CmuxResource("terminal", terminal, "terminal · Quest shell")),
            connected, false, "", text, emptyList(), onRun, {}, {}, {}, {}, {}, {}, {}, {})
        }
      }
    }
  }

  private fun screenshot(name: String) {
    val image = compose.onRoot().captureToImage().asAndroidBitmap()
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VRcode-QA")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    context.contentResolver.openOutputStream(uri)!!.use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
  }
}
