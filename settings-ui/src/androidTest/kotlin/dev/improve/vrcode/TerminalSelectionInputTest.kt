package dev.improve.vrcode

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.screen.layout.LayoutTree
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.terminal.font.TerminalFont
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercise real Android pinch events against the same input binding and grid as the Quest. */
class TerminalSelectionInputTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var screen: ScreenView
  private lateinit var input: TerminalSelectionInput<String>
  private val copies = mutableListOf<String>()
  private val clicks = mutableListOf<Pair<String, TerminalTextSelection.Cell>>()
  private var target = "first terminal"
  private var tabClicks = 0
  private val grid = CellGrid(16, 3).apply {
    defaultBg = 0xFF1E1E1E.toInt()
    write(0, 0, "Hello terminal")
    write(1, 0, "Pinch and drag")
    write(2, 0, "Third line")
    setCursorVisible(false)
  }
  private val otherGrid = CellGrid(16, 3).apply {
    defaultBg = 0xFF1E1E1E.toInt()
    write(0, 0, "Other pane")
    setCursorVisible(false)
  }

  private fun mount(tabs: Boolean = false) {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(560.dp, 180.dp).testTag("selection-preview")) {
          AndroidView(factory = { context ->
            ScreenView(context, TerminalFont(context, 25f)).also { view ->
              screen = view
              view.update(LayoutTree.Split(true, 0.5f, LayoutTree.Leaf(1), LayoutTree.Leaf(2)),
                listOf(ScreenView.Pane(1, grid, if (tabs) listOf("one", "two") else emptyList()),
                  ScreenView.Pane(2, otherGrid)), 1)
              view.listener = object : ScreenView.Listener {
                override fun onTab(pane: Int, index: Int) { tabClicks++ }
              }
              input = TerminalSelectionInput(view,
                targetAt = { if (it == 1) target else "other terminal" },
                onFocus = { view.focusedPane = it },
                onClick = { terminal, cell -> clicks += terminal to cell },
                onCopy = { copies += it })
            }
          })
        }
      }
    }
    compose.waitForIdle()
  }

  private fun point(row: Int, col: Int, pane: Int = 1): Pair<Float, Float> {
    val v = screen.gridView(pane)!!
    return v.left + v.paddingPx + (col + 0.5f) * v.font.cellWidth to
      v.top + v.paddingPx + (row + 0.5f) * v.font.cellHeight
  }

  private fun event(action: Int, point: Pair<Float, Float>) {
    val now = SystemClock.uptimeMillis()
    val e = MotionEvent.obtain(now, now, action, point.first, point.second, 0)
    try { assertTrue(screen.dispatchTouchEvent(e)) } finally { e.recycle() }
  }

  private fun capture(name: String? = null): Bitmap {
    compose.waitForIdle()
    return compose.onNodeWithTag("selection-preview").captureToImage().asAndroidBitmap().also { bitmap ->
      if (name != null) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
          ?: screen.context.getExternalFilesDir(null)!!
        directory.mkdirs()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      }
    }
  }

  private fun backgroundPixel(bitmap: Bitmap, row: Int, col: Int, pane: Int = 1): Int {
    val v = screen.gridView(pane)!!
    return bitmap.getPixel((v.left + v.paddingPx + col * v.font.cellWidth + 1).toInt(),
      (v.top + v.paddingPx + row * v.font.cellHeight + 1).toInt())
  }

  @Test fun heldPinchHighlightsAcrossLinesAndCopiesOnReleaseWithoutTypingMouseReports() {
    mount()
    val before = capture("selection-before.png")
    compose.runOnIdle {
      event(MotionEvent.ACTION_DOWN, point(0, 6))
      event(MotionEvent.ACTION_MOVE, point(1, 4))
      assertTrue(copies.isEmpty())
      assertTrue(clicks.isEmpty())
    }
    val held = capture("selection-held.png")
    assertNotEquals(backgroundPixel(before, 0, 7), backgroundPixel(held, 0, 7))
    assertNotEquals(backgroundPixel(before, 1, 2), backgroundPixel(held, 1, 2))
    assertEquals(backgroundPixel(before, 0, 2), backgroundPixel(held, 0, 2))
    assertEquals(backgroundPixel(before, 1, 6), backgroundPixel(held, 1, 6))
    assertEquals(backgroundPixel(before, 0, 2, 2), backgroundPixel(held, 0, 2, 2))
    compose.runOnIdle { event(MotionEvent.ACTION_UP, point(1, 4)) }
    assertEquals(listOf("terminal\nPinch"), copies)
    assertTrue(clicks.isEmpty())
    assertTrue(held.sameAs(capture("selection-released.png")))
    compose.runOnIdle { input.close() }
  }

  @Test fun backwardQuickDragAndOutOfPaneReleaseRetainTheStartingPane() {
    mount()
    compose.runOnIdle {
      event(MotionEvent.ACTION_DOWN, point(1, 4))
      // No intermediate MOVE: UP must still complete a short drag.
      event(MotionEvent.ACTION_UP, point(0, 6))
      assertEquals("terminal\nPinch", copies.last())
      event(MotionEvent.ACTION_DOWN, point(0, 6))
      event(MotionEvent.ACTION_MOVE, point(1, 4, 2))
      event(MotionEvent.ACTION_UP, point(1, 4, 2))
      assertEquals("terminal\nPinch and drag", copies.last())
      event(MotionEvent.ACTION_DOWN, point(0, 0))
      event(MotionEvent.ACTION_UP, -100f to -100f)
      assertEquals("first terminal" to TerminalTextSelection.Cell(0, 0), clicks.single())
      input.close()
    }
  }

  @Test fun cancelledDisabledOrReplacedTargetsNeverCopyOrClickOnLateRelease() {
    mount()
    val before = capture()
    compose.runOnIdle {
      fun start() {
        event(MotionEvent.ACTION_DOWN, point(0, 0))
        event(MotionEvent.ACTION_MOVE, point(0, 4))
      }
      fun release() { event(MotionEvent.ACTION_UP, point(0, 4)) }
      start(); event(MotionEvent.ACTION_CANCEL, point(0, 4)); release()
      start(); input.cancel(); release() // Right-hand tracking loss.
      start(); input.enabled = false; input.enabled = true; release() // Pause/settings.
      start(); target = "new terminal"; input.validate(); release() // Active tab switched.
      start(); screen.gridView(1)!!.grid = CellGrid(16, 3); input.validate(); release() // Resize.
      assertTrue(copies.isEmpty())
      assertTrue(clicks.isEmpty())
      screen.gridView(1)!!.grid = grid
    }
    assertTrue(before.sameAs(capture()))
    compose.runOnIdle { input.close() }
  }

  @Test fun stationaryPinchClicksAndTabReleaseCannotClickTheNewTerminal() {
    mount(tabs = true)
    compose.runOnIdle {
      event(MotionEvent.ACTION_DOWN, point(0, 1))
      event(MotionEvent.ACTION_UP, point(0, 1))
      assertEquals(listOf("first terminal" to TerminalTextSelection.Cell(0, 1)), clicks)
      event(MotionEvent.ACTION_DOWN, 30f to 15f)
      event(MotionEvent.ACTION_UP, point(0, 1))
      assertEquals(1, tabClicks)
      assertEquals(1, clicks.size)
      assertTrue(copies.isEmpty())
      input.close()
    }
  }
}
