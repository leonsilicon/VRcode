package dev.improve.vrcode.pie

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.vrcode.QuickInput
import dev.improve.vrcode.QuickInputRing
import dev.improve.vrcode.QuickInputSlot
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The left index-pinch ring as the hand sees it, built from Settings' [QuickInputRing]. */
class QuickInputRingTest {
  @get:Rule val compose = createComposeRule()

  private fun items(ring: QuickInputRing) = ring.placed.map { (_, input) -> PieItem(input.label, input.icon) {} }
  private fun angles(ring: QuickInputRing) = ring.placed.map { (slot, _) -> slot.degrees }

  @Test fun theDefaultKeysSitOnTheDiagonalsWithTheirPillsInsideTheRing() {
    val ring = QuickInputRing.Default
    val state = PieState("Input", items(ring), angles = angles(ring))
    mount(state)
    val menu = menuBounds()
    fun centre(label: String) = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot.center - menu.center
    // Screen y grows downward. Each key sits on its diagonal, at equal distances right/left and up/down.
    for ((label, sx, sy) in listOf(Triple("Esc", -1, -1), Triple("Ctrl-C", 1, -1), Triple("Tab", -1, 1), Triple("Enter", 1, 1))) {
      val c = centre(label)
      assertTrue("$label at $c", c.x * sx > 0 && c.y * sy > 0)
      assertEquals("$label on its diagonal", abs(c.x), abs(c.y), 1f)
    }
    capture("quick-inputs-default.png")
    for ((i, label) in state.items.map { it.label }.withIndex()) {
      compose.runOnIdle { state.highlighted = i; state.progress = 0.6f }
      compose.mainClock.advanceTimeBy(200)
      compose.onNodeWithText(label).assertIsDisplayed()
      assertLabelFits(label)
      assertInsideMenu(compose.onNodeWithTag(pieLabelTag(i)).fetchSemanticsNode().boundsInRoot)
      if (label == "Ctrl-C") capture("quick-inputs-default-hover.png")
    }
  }

  @Test fun changingTheRingRedrawsItAndEveryInputFits() {
    val state = PieState("Input", items(QuickInputRing.Default), angles = angles(QuickInputRing.Default))
    mount(state)
    // Every input in the catalogue, eight at a time on a full ring: icons drawn, labels fit, label pills inside.
    var shown = QuickInputRing.Default.placed.map { it.second.label }
    for (batch in QuickInput.entries.chunked(QuickInputSlot.entries.size)) {
      val ring = QuickInputRing.ring(*QuickInputSlot.entries.zip(batch).toTypedArray())
      compose.runOnIdle { state.setItems(items(ring), angles(ring)) }
      for (gone in shown - batch.map { it.label }.toSet()) compose.onNodeWithContentDescription(gone).assertDoesNotExist()
      shown = batch.map { it.label }
      for ((i, input) in batch.withIndex()) {
        compose.onNodeWithContentDescription(input.label).assertExists()
        compose.runOnIdle { state.highlighted = i; state.progress = 0.4f }
        compose.mainClock.advanceTimeBy(200)
        assertLabelFits(input.label)
        assertInsideMenu(compose.onNodeWithTag(pieLabelTag(i)).fetchSemanticsNode().boundsInRoot)
      }
      compose.runOnIdle { state.highlighted = null }
      compose.mainClock.advanceTimeBy(200)
      capture("quick-inputs-catalogue-${batch.first().storageValue}.png")
    }
  }

  private fun menuBounds() = compose.onNodeWithTag("quick-ring").fetchSemanticsNode().boundsInRoot

  private fun assertInsideMenu(b: Rect) {
    val menu = menuBounds()
    assertTrue("$b must stay inside $menu", b.left >= menu.left && b.right <= menu.right && b.top >= menu.top && b.bottom <= menu.bottom)
  }

  private fun mount(state: PieState) {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(600.dp).background(Color(0xFF11151D)).testTag("quick-ring")) { PieMenu(state) }
      }
    }
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    val directory = (output?.let(::File) ?: File(context.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
    File(directory, name).outputStream().use {
      compose.onNodeWithTag("quick-ring").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  private fun assertLabelFits(label: String) {
    val layouts = mutableListOf<TextLayoutResult>()
    compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertTrue("$label must fit without clipping", layouts.single().hasVisualOverflow.not())
  }
}
