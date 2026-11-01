package dev.improve.vrcode

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * The dock's two panels at the headset's pixel scale (about 0.83 mm per pixel): the 12 x 6 cm bar
 * and the 48 x 14.5 cm sheet it tops, drawn like the macOS Dock. Captures go to the test app's
 * external `screenshots` directory.
 */
class DockPanelTest {
  @get:Rule val compose = createComposeRule()

  private val options = listOf(
    DockOption("Connected", DockOptionKind.Connection),
    DockOption("zsh — ~/repo", DockOptionKind.Window, selected = true),
    DockOption("claude", DockOptionKind.Window, hidden = true),
    DockOption("Keyboard", DockOptionKind.Keyboard, selected = true),
    DockOption("Arrange", DockOptionKind.Arrange),
    DockOption("Settings", DockOptionKind.Settings),
  )

  /** The row as DockLayout lays it out: 6 cm slots, a separator's half slot before Keyboard, centred. */
  private class Row(count: Int, separatorBefore: Int) {
    val spacing = min(0.06f, 0.42f / (count + DockShelf.SEPARATOR))
    private val start = -(count - 1 + DockShelf.SEPARATOR) * spacing / 2f
    private val metres = List(count) { i -> start + (i + if (i >= separatorBefore) DockShelf.SEPARATOR else 0f) * spacing }
    val xs = metres.map { (it + 0.24f) / 0.48f }
    val slot = spacing / 0.48f
    val separatorX = (xs[separatorBefore - 1] + xs[separatorBefore]) / 2f
  }

  @Test fun theBarIsFaintAtRestAndBulgesWithAnUpCueUnderAHand() {
    var bulge by mutableStateOf(0f)
    var raised by mutableStateOf(false)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(144.dp, 72.dp)) { DockBar(bulge, raised) }
      }
    }
    compose.onNodeWithTag("vrcode-dock-bar").assertIsDisplayed()
    compose.onNodeWithText("⌃").assertDoesNotExist()
    capture("dock-bar-rest.png", "vrcode-dock-bar")
    compose.runOnIdle { bulge = 1f }
    compose.onNodeWithText("⌃").assertExists()
    capture("dock-bar-bulge.png", "vrcode-dock-bar")
    // Expanded, this bar is the sheet's top edge, above the options; its cue points down to swipe.
    compose.runOnIdle { raised = true }
    compose.onNodeWithText("⌄").assertExists()
    compose.onNodeWithText("⌃").assertDoesNotExist()
    capture("dock-bar-raised.png", "vrcode-dock-bar")
  }

  @Test fun theRaisedDockIsAMacStyleShelfOfSquareIconsThatShowsTheNameOfTheOneAHandIsNear() {
    var highlighted by mutableStateOf<Int?>(null)
    var hoverX by mutableStateOf<Float?>(null)
    var drop by mutableStateOf(0f)
    val row = Row(options.size, separatorBefore = 3)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // The sheet panel at the headset's scale (48 x 14.5 cm) with the bar's panel over its top,
        // laid out as DockFeature does (rows and label in fractions of the expanded sheet).
        Box(Modifier.requiredSize(576.dp, 174.dp).testTag("dock-sheet-and-bar")) {
          DockBody(options, row.xs, row.slot, highlighted, rowY = 0.724f, labelY = 0.407f, separatorX = row.separatorX, hoverX = hoverX, drop = drop)
          Box(Modifier.offset(x = 216.dp, y = (174f * drop - 12f).dp).requiredSize(144.dp, 72.dp)) { DockBar(0f, raised = true) }
        }
      }
    }
    compose.onNodeWithText("Keyboard").assertDoesNotExist()
    // Square tiles, four fifths of a slot, each named for accessibility.
    val tile = row.slot * 576f * DockShelf.TILE
    for (i in options.indices) {
      val bounds = compose.onNodeWithTag("dock-option-$i").assertContentDescriptionEquals(options[i].label).getBoundsInRoot()
      assertEquals(tile, bounds.width.value, 1f)
      assertEquals(tile, bounds.height.value, 1f)
    }
    // The shelf hugs its icons rather than filling the panel.
    val shelf = compose.onNodeWithTag("vrcode-dock-sheet").getBoundsInRoot()
    val hugging = (row.xs.last() - row.xs.first() + 2 * row.slot * DockShelf.END) * 576f
    assertEquals(hugging, shelf.width.value, 1.5f)
    assertTrue(shelf.width.value < 560f)
    capture("dock-raised.png", "dock-sheet-and-bar")
    // A fingertip over Keyboard: it grows most, its neighbours less, and its name shows.
    compose.runOnIdle { highlighted = 3; hoverX = row.xs[3] }
    compose.onNodeWithText("Keyboard").assertExists()
    val hovered = compose.onNodeWithTag("dock-option-3").getBoundsInRoot().width.value
    val neighbour = compose.onNodeWithTag("dock-option-4").getBoundsInRoot().width.value
    assertEquals(tile * DockShelf.MAGNIFY, hovered, 1f)
    assertTrue(neighbour > tile + 1f && neighbour < hovered - 1f)
    assertEquals(tile, compose.onNodeWithTag("dock-option-0").getBoundsInRoot().width.value, 1f)
    capture("dock-raised-hover.png", "dock-sheet-and-bar")
    // An end icon's name stays inside the shelf.
    compose.runOnIdle { highlighted = 5; hoverX = row.xs[5] }
    val label = compose.onNodeWithText("Settings").getBoundsInRoot()
    assertTrue(label.right.value <= shelf.right.value + 0.5f)
    capture("dock-raised-hover-end.png", "dock-sheet-and-bar")
    // Halfway up, the sheet's top edge still carries the bar and the options rise out of its bottom.
    compose.runOnIdle { highlighted = null; hoverX = null; drop = 0.4f }
    capture("dock-half-raised.png", "dock-sheet-and-bar")
  }

  @Test fun manyWindowsShrinkTheIconsInsteadOfOverlappingThem() {
    val many = listOf(DockOption("Connected", DockOptionKind.Connection)) +
      List(12) { DockOption("Terminal ${it + 1}", DockOptionKind.Window, selected = it == 4) } +
      listOf(DockOption("Keyboard", DockOptionKind.Keyboard), DockOption("Arrange", DockOptionKind.Arrange), DockOption("Settings", DockOptionKind.Settings))
    val row = Row(many.size, separatorBefore = 13)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(576.dp, 174.dp)) {
          DockBody(many, row.xs, row.slot, highlighted = null, rowY = 0.724f, labelY = 0.407f, separatorX = row.separatorX)
        }
      }
    }
    val bounds = many.indices.map { compose.onNodeWithTag("dock-option-$it").getBoundsInRoot() }
    for (i in 1 until bounds.size) assertTrue("icons $i and ${i - 1} overlap", bounds[i].left >= bounds[i - 1].right)
    assertTrue(bounds.first().left.value >= 0f && bounds.last().right.value <= 576f)
    capture("dock-raised-many.png")
  }

  @Test fun touchesAreReportedOnContactBeforeAnyRelease() {
    val bar = mutableListOf<DockTouch>()
    val body = mutableListOf<DockTouch>()
    val row = Row(options.size, separatorBefore = 3)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // Stacked so each panel's touches stay apart; in the headset the handle's panel overlaps the sheet's top.
        Column {
          Box(Modifier.requiredSize(144.dp, 72.dp)) { DockBar(0f) { bar += it } }
          Box(Modifier.requiredSize(576.dp, 174.dp)) { DockBody(options, row.xs, row.slot, null, rowY = 0.724f, labelY = 0.407f) { body += it } }
        }
      }
    }
    compose.onNodeWithTag("vrcode-dock-bar").performTouchInput { down(center) }
    compose.runOnIdle {
      assertEquals(listOf(DockTouchPhase.Down), bar.map { it.phase })
      assertEquals(0.5f, bar[0].x, 0.01f)
      assertEquals(0.5f, bar[0].y, 0.01f)
    }
    compose.onNodeWithTag("vrcode-dock-bar").performTouchInput { up() }
    compose.runOnIdle { assertEquals(DockTouchPhase.Up, bar.last().phase) }
    compose.onNodeWithTag("vrcode-dock").performTouchInput { down(Offset(width * 0.25f, height * 0.7f)) }
    compose.runOnIdle {
      assertEquals(listOf(DockTouchPhase.Down), body.map { it.phase })
      assertEquals(0.25f, body[0].x, 0.01f)
      assertEquals(0.7f, body[0].y, 0.01f)
    }
  }

  private fun capture(name: String, tag: String = "vrcode-dock") {
    compose.mainClock.advanceTimeBy(1000)
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    File(directory, name).outputStream().use {
      compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
