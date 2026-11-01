package dev.improve.vrcode.pie

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PieMenuTest {
  @get:Rule val compose = createComposeRule()

  private val left = listOf(
    PieItem("Add terminal", PieIcon.AddTerminal) {},
    PieItem("Codex", PieIcon.Codex) {},
    PieItem("Claude Code", PieIcon.Claude) {},
    PieItem("Bring forward", PieIcon.BringForward) {},
  )

  @Test fun hoveringAnIconRevealsItsLabelFarOutOnItsSpokeAndOnlyTheLabelFills() {
    val state = PieState("New", left)
    mount(state)
    for ((i, item) in state.items.withIndex()) {
      compose.onNodeWithText(item.label).assertDoesNotExist()
      compose.onNodeWithTag(pieLabelTag(i)).assertDoesNotExist()
      val bounds = compose.onNodeWithContentDescription(item.label).fetchSemanticsNode().boundsInRoot
      assertEquals(bounds.height, bounds.width, 1f) // icons stay circles
    }
    capture("quick-actions-icons.png")
    compose.runOnIdle { state.highlighted = 0; state.progress = 0.5f }
    compose.mainClock.advanceTimeBy(200)
    compose.onNodeWithText("Add terminal").assertIsDisplayed()
    assertLabelFits("Add terminal")
    val menu = menuBounds()
    val icon = compose.onNodeWithContentDescription("Add terminal").assertHasNoClickAction()
    // The dwell's fill belongs to the label pill, not the icon.
    icon.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
    val label = compose.onNodeWithTag(pieLabelTag(0)).assertHasNoClickAction()
    label.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)))
    // Up the top spoke: the label begins as far above the icon's centre as that is above the menu's.
    val i = icon.fetchSemanticsNode().boundsInRoot
    val l = label.fetchSemanticsNode().boundsInRoot
    assertTrue("label $l above icon $i", l.bottom < i.top)
    assertEquals("icon to label = centre to icon", menu.center.y - i.center.y, i.center.y - l.bottom, 1.5f)
    assertTrue(l.width > l.height * 2f)
    assertInsideMenu(l)
    capture("quick-actions-hover.png")
    compose.runOnIdle { state.highlighted = 3; state.progress = 0.3f }
    compose.mainClock.advanceTimeBy(200)
    assertLabelFits("Bring forward")
    compose.onNodeWithText("Add terminal").assertDoesNotExist()
    // Out to the left: the label's near end, its icon and the menu's centre are evenly spaced.
    val bi = compose.onNodeWithContentDescription("Bring forward").fetchSemanticsNode().boundsInRoot
    val bl = compose.onNodeWithTag(pieLabelTag(3)).fetchSemanticsNode().boundsInRoot
    assertEquals("icon to label = centre to icon", menu.center.x - bi.center.x, bi.center.x - bl.right, 1.5f)
    assertInsideMenu(bl)
    capture("quick-actions-hover-left.png")
    compose.runOnIdle { state.highlighted = null; state.progress = 0f }
    compose.mainClock.advanceTimeBy(200)
    compose.onNodeWithText("Bring forward").assertDoesNotExist()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test fun theRayCrossesTheGapFromAnIconToItsLabelAndTheShownLabelRunsIt() {
    val state = PieState("New", left)
    mount(state)
    val menu = menuBounds()
    val icon = compose.onNodeWithContentDescription("Add terminal").fetchSemanticsNode().boundsInRoot
    fun hover(x: Float, y: Float) {
      compose.onNodeWithTag("quick-actions").performMouseInput { moveTo(Offset(x - menu.left, y - menu.top)) }
      compose.waitForIdle()
    }
    hover(icon.center.x, icon.center.y)
    compose.runOnIdle { assertEquals(0, state.rayHovered); assertNull(state.rayRun) }
    // The app highlights the hovered item, which shows its label.
    compose.runOnIdle { state.highlighted = 0 }
    compose.mainClock.advanceTimeBy(200)
    val label = compose.onNodeWithTag(pieLabelTag(0)).fetchSemanticsNode().boundsInRoot
    // Straight up from the icon, across the empty stretch of spoke, the item stays hovered; on the
    // label it runs. Moving straight off the icon is the case per-element hoverables missed.
    var y = icon.center.y
    while (y > label.top) {
      hover(icon.center.x, y)
      val onLabel = y < label.bottom - 1f
      compose.runOnIdle {
        assertEquals("ray at y=$y", if (onLabel) null else 0, state.rayHovered)
        assertEquals("ray at y=$y", if (onLabel) 0 else null, state.rayRun)
      }
      y -= 6f
    }
    // Back to the icon, then out sideways off the spoke: nothing.
    hover(icon.center.x, icon.center.y)
    hover(icon.center.x + icon.width * 1.5f, (icon.top + label.bottom) / 2)
    compose.runOnIdle { assertNull(state.rayHovered); assertNull(state.rayRun) }
    hover(menu.left + 5f, menu.top + 5f)
    compose.runOnIdle { assertNull(state.rayHovered); assertNull(state.rayRun) }
  }

  @Test fun sixActionsKeepTheirPillsInsideTheMenuAndClicksCannotLaunch() {
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
      for (node in listOf(compose.onNodeWithContentDescription(labels[i]), compose.onNodeWithTag(pieLabelTag(i)))) {
        node.assertHasNoClickAction().performTouchInput { click() }
        assertInsideMenu(node.fetchSemanticsNode().boundsInRoot)
      }
    }
    capture("quick-actions-keys.png")
    compose.runOnIdle { assertEquals(0, launches) }
  }

  @Test fun eightQuickInputsKeepEverySpokeInsideTheMenu() {
    // The left index-pinch ring, in its real order: item 0 at the top, then clockwise.
    val labels = listOf("Up", "Keyboard", "Right", "Enter", "Down", "Tab", "Left", "Esc")
    val icons = listOf(PieIcon.ArrowUp, PieIcon.Keyboard, PieIcon.ArrowRight, PieIcon.Enter,
      PieIcon.ArrowDown, PieIcon.Tab, PieIcon.ArrowLeft, PieIcon.Escape)
    val state = PieState("Input", labels.zip(icons) { label, icon -> PieItem(label, icon) {} })
    mount(state)
    val menu = menuBounds()
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
      val icon = compose.onNodeWithContentDescription(labels[i]).fetchSemanticsNode().boundsInRoot
      val label = compose.onNodeWithTag(pieLabelTag(i)).fetchSemanticsNode().boundsInRoot
      assertInsideMenu(label)
      when (labels[i]) {
        "Right" -> assertEquals(icon.center.x - menu.center.x, label.left - icon.center.x, 1.5f)
        "Left" -> assertEquals(menu.center.x - icon.center.x, icon.center.x - label.right, 1.5f)
      }
      if (labels[i] == "Right") capture("quick-inputs-hover.png")
    }
  }

  @Test fun theFillRunsAlongTheLabelFromTheIconsEndAndNeverOverTheIcon() {
    val labels = listOf("Up", "Keyboard", "Right", "Enter", "Down", "Tab", "Left", "Esc")
    val icons = listOf(PieIcon.ArrowUp, PieIcon.Keyboard, PieIcon.ArrowRight, PieIcon.Enter,
      PieIcon.ArrowDown, PieIcon.Tab, PieIcon.ArrowLeft, PieIcon.Escape)
    val state = PieState("Input", labels.zip(icons) { label, icon -> PieItem(label, icon) {} })
    mount(state)
    // Half full: the end nearer the icon is filled, the far end is not. Samples sit near each end,
    // off the centred text: (x, y) as fractions of the label pill.
    val samples = mapOf(
      "Right" to ((0.12f to 0.2f) to (0.88f to 0.2f)),
      "Left" to ((0.88f to 0.2f) to (0.12f to 0.2f)),
      "Up" to ((0.12f to 0.2f) to (0.88f to 0.2f)),
    )
    for ((label, ends) in samples) {
      val i = labels.indexOf(label)
      compose.runOnIdle { state.highlighted = i; state.progress = 0.5f }
      compose.mainClock.advanceTimeBy(200)
      compose.waitForIdle()
      val menu = menuBounds()
      val pill = compose.onNodeWithTag(pieLabelTag(i)).fetchSemanticsNode().boundsInRoot
      val icon = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
      val image = compose.onNodeWithTag("quick-actions").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
      fun blue(x: Float, y: Float): Int = image.getPixel((x - menu.left).toInt(), (y - menu.top).toInt()) and 0xFF
      fun inPill(at: Pair<Float, Float>) = blue(pill.left + pill.width * at.first, pill.top + pill.height * at.second)
      val (inner, outer) = ends
      assertTrue("$label: the end near the icon is filled (blue ${inPill(inner)})", inPill(inner) > 150)
      assertTrue("$label: the far end is not filled yet (blue ${inPill(outer)})", inPill(outer) < 120)
      // The icon's background, between its rim and glyph, never takes the fill.
      val iconBlue = blue(icon.left + icon.width * 0.2f, icon.top + icon.height * 0.5f)
      assertTrue("$label: the icon stays unfilled (blue $iconBlue)", iconBlue < 120)
      if (label == "Left") capture("quick-inputs-fill.png")
    }
  }

  private fun menuBounds() = compose.onNodeWithTag("quick-actions").fetchSemanticsNode().boundsInRoot

  private fun assertInsideMenu(b: androidx.compose.ui.geometry.Rect) {
    val menu = menuBounds()
    assertTrue("$b must stay inside $menu", b.left >= menu.left && b.right <= menu.right && b.top >= menu.top && b.bottom <= menu.bottom)
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
    val t = layouts.single()
    assertTrue("$label must fit without clipping: box ${t.size} text ${t.multiParagraph.width}x${t.multiParagraph.height} font ${t.layoutInput.style.fontSize} max ${t.layoutInput.constraints}", t.hasVisualOverflow.not())
  }
}
