package dev.improve.vrcode.pie

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PieMenuTest {
  @get:Rule val compose = createComposeRule()

  @Test fun iconsExpandIntoPillsWithProgressAndCollapseWhenHoverEnds() {
    val state = PieState("New", listOf(
      PieItem("Add terminal", PieIcon.AddTerminal) {},
      PieItem("Codex", PieIcon.Codex) {},
      PieItem("Claude Code", PieIcon.Claude) {},
    ))
    mount(state)
    for (item in state.items) {
      compose.onNodeWithText(item.label).assertDoesNotExist()
      val bounds = compose.onNodeWithContentDescription(item.label).fetchSemanticsNode().boundsInRoot
      assertEquals(bounds.height, bounds.width, 1f)
    }
    capture("quick-actions-icons.png")
    compose.runOnIdle { state.highlighted = 0; state.progress = 0.5f }
    compose.mainClock.advanceTimeBy(200)
    compose.onNodeWithText("Add terminal").assertIsDisplayed()
    assertLabelFits("Add terminal")
    val pill = compose.onNodeWithContentDescription("Add terminal")
    pill.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)))
    pill.assertHasNoClickAction()
    val bounds = pill.fetchSemanticsNode().boundsInRoot
    assertTrue(bounds.width > bounds.height * 2f)
    val menu = compose.onNodeWithTag("quick-actions").fetchSemanticsNode().boundsInRoot
    assertTrue(bounds.left >= menu.left && bounds.right <= menu.right)
    capture("quick-actions-hover.png")
    compose.runOnIdle { state.highlighted = null; state.progress = 0f }
    compose.mainClock.advanceTimeBy(200)
    compose.onNodeWithText("Add terminal").assertDoesNotExist()
  }

  @Test fun sixActionsKeepTheirLabelsInsideTheMenuAndClicksCannotLaunch() {
    var launches = 0
    val labels = listOf("Keyboard", "Enter", "Esc", "Ctrl-C", "Paste voice", "Clear voice")
    val icons = listOf(PieIcon.Keyboard, PieIcon.Enter, PieIcon.Escape, PieIcon.Interrupt, PieIcon.Paste, PieIcon.Clear)
    val state = PieState("Keys", labels.zip(icons) { label, icon -> PieItem(label, icon) { launches++ } })
    mount(state)
    for (i in labels.indices) {
      compose.runOnIdle { state.highlighted = i; state.progress = 0.75f }
      compose.mainClock.advanceTimeBy(200)
      compose.onNodeWithText(labels[i]).assertIsDisplayed()
      assertLabelFits(labels[i])
      val icon = compose.onNodeWithContentDescription(labels[i])
      icon.assertHasNoClickAction().performTouchInput { click() }
      val bounds = icon.fetchSemanticsNode().boundsInRoot
      val menu = compose.onNodeWithTag("quick-actions").fetchSemanticsNode().boundsInRoot
      assertTrue(bounds.left >= menu.left && bounds.right <= menu.right && bounds.top >= menu.top && bounds.bottom <= menu.bottom)
    }
    capture("quick-actions-keys.png")
    compose.runOnIdle { assertEquals(0, launches) }
  }

  @Test fun eightQuickInputsKeepTheirPillsInsideTheMenu() {
    // The left index-pinch ring, in its real order: item 0 at the top, then clockwise.
    val labels = listOf("Up", "Keyboard", "Right", "Enter", "Down", "Tab", "Left", "Esc")
    val icons = listOf(PieIcon.ArrowUp, PieIcon.Keyboard, PieIcon.ArrowRight, PieIcon.Enter,
      PieIcon.ArrowDown, PieIcon.Tab, PieIcon.ArrowLeft, PieIcon.Escape)
    val state = PieState("Input", labels.zip(icons) { label, icon -> PieItem(label, icon) {} })
    mount(state)
    val menu = compose.onNodeWithTag("quick-actions").fetchSemanticsNode().boundsInRoot
    val centres = labels.associateWith {
      compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.center - menu.center
    }
    // Each key sits where it points (screen y grows downward).
    assertTrue(centres.getValue("Up").y < 0 && centres.getValue("Down").y > 0)
    assertTrue(centres.getValue("Left").x < 0 && centres.getValue("Right").x > 0)
    with(centres.getValue("Esc")) { assertTrue(x < 0 && y < 0) }
    with(centres.getValue("Keyboard")) { assertTrue(x > 0 && y < 0) }
    with(centres.getValue("Tab")) { assertTrue(x < 0 && y > 0) }
    with(centres.getValue("Enter")) { assertTrue(x > 0 && y > 0) }
    capture("quick-inputs-icons.png")
    for (i in labels.indices) {
      compose.runOnIdle { state.highlighted = i; state.progress = 0.6f }
      compose.mainClock.advanceTimeBy(200)
      compose.onNodeWithText(labels[i]).assertIsDisplayed()
      assertLabelFits(labels[i])
      val bounds = compose.onNodeWithContentDescription(labels[i]).fetchSemanticsNode().boundsInRoot
      assertTrue("${labels[i]} pill $bounds must stay inside $menu",
        bounds.left >= menu.left && bounds.right <= menu.right && bounds.top >= menu.top && bounds.bottom <= menu.bottom)
      if (labels[i] == "Right") capture("quick-inputs-hover.png")
    }
  }

  @Test fun theFillSweepsOutwardTheWayASwipeThroughThePillMoves() {
    val labels = listOf("Up", "Keyboard", "Right", "Enter", "Down", "Tab", "Left", "Esc")
    val icons = listOf(PieIcon.ArrowUp, PieIcon.Keyboard, PieIcon.ArrowRight, PieIcon.Enter,
      PieIcon.ArrowDown, PieIcon.Tab, PieIcon.ArrowLeft, PieIcon.Escape)
    val state = PieState("Input", labels.zip(icons) { label, icon -> PieItem(label, icon) {} })
    mount(state)
    // Half full: the half nearer the pie's centre is filled, the far half is not. Samples sit
    // near each end and off the centred icon and label: (x, y) as fractions of the pill.
    val samples = mapOf(
      "Up" to ((0.12f to 0.8f) to (0.12f to 0.2f)),
      "Right" to ((0.12f to 0.18f) to (0.88f to 0.18f)),
      "Down" to ((0.12f to 0.2f) to (0.12f to 0.8f)),
      "Left" to ((0.88f to 0.18f) to (0.12f to 0.18f)),
    )
    for ((label, ends) in samples) {
      compose.runOnIdle { state.highlighted = labels.indexOf(label); state.progress = 0.5f }
      compose.mainClock.advanceTimeBy(200)
      compose.waitForIdle()
      val menu = compose.onNodeWithTag("quick-actions").fetchSemanticsNode().boundsInRoot
      val pill = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
      val image = compose.onNodeWithTag("quick-actions").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
      fun blue(at: Pair<Float, Float>): Int = image.getPixel(
        (pill.left - menu.left + pill.width * at.first).toInt(),
        (pill.top - menu.top + pill.height * at.second).toInt(),
      ) and 0xFF
      val (inner, outer) = ends
      assertTrue("$label: the inner end is filled (blue ${blue(inner)})", blue(inner) > 150)
      assertTrue("$label: the outer end is not filled yet (blue ${blue(outer)})", blue(outer) < 120)
      if (label == "Left") capture("quick-inputs-swipe-fill.png")
    }
  }

  private fun mount(state: PieState) {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(600.dp).background(Color(0xFF11151D)).testTag("quick-actions")) { PieMenu(state) }
      }
    }
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    val directory = (output?.let(::File) ?: File(context.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
    File(directory, name).outputStream().use {
      compose.onNodeWithTag("quick-actions").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  private fun assertLabelFits(label: String) {
    val layouts = mutableListOf<TextLayoutResult>()
    compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertTrue("$label must fit without clipping", layouts.single().hasVisualOverflow.not())
  }
}
