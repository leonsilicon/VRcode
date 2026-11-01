package dev.improve.vrcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.cell.grid.CursorShape
import dev.improve.xmux.screen.layout.LayoutTree
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.TerminalGridView
import org.junit.Assert.*
import org.junit.Test

/**
 * Like a macOS terminal, only the key window's focused split draws a solid block cursor: an
 * unfocused split or a window in the background draws it hollow.
 */
class InactiveTerminalTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val font = TerminalFont(context, 40f)

  @Test fun unfocusedBlockCursorIsHollowAndOtherShapesAreUnchanged() {
    val cw = font.cellWidth
    val ch = font.cellHeight
    val center = { b: Bitmap -> Color.red(b.getPixel((cw * 1.5f).toInt(), (ch / 2).toInt())) }
    val leftEdge = { b: Bitmap -> Color.red(b.getPixel((cw + 1f).toInt(), (ch / 2).toInt())) }

    val solid = render(CursorShape.Block, focused = true)
    assertTrue("focused block cursor fills its cell", center(solid) > 150)

    val hollow = render(CursorShape.Block, focused = false)
    assertEquals("unfocused block cursor is empty inside", 0, center(hollow))
    assertTrue("unfocused block cursor keeps its outline", leftEdge(hollow) > 150)

    // A bar is already thin; it stays as it is.
    val bar = render(CursorShape.Bar, focused = false)
    assertTrue("unfocused bar cursor is drawn", leftEdge(bar) > 150)
  }

  @Test fun onlyTheFocusedPaneOfAnActiveScreenIsFocused() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val screen = ScreenView(context, font)
      val panes = listOf(ScreenView.Pane(1, CellGrid(10, 3)), ScreenView.Pane(2, CellGrid(10, 3)))
      screen.update(LayoutTree.Split(true, 0.5f, LayoutTree.Leaf(1), LayoutTree.Leaf(2)), panes, focusedPane = 2)
      fun focus() = listOf(1, 2).map { screen.gridView(it)!!.focused }
      assertEquals(listOf(false, true), focus())

      screen.focusedPane = 1
      assertEquals(listOf(true, false), focus())

      screen.active = false
      assertEquals(listOf(false, false), focus())

      // A pane added while the window is in the background is not focused either.
      screen.update(LayoutTree.Leaf(3), listOf(ScreenView.Pane(3, CellGrid(10, 3))), focusedPane = 3)
      assertFalse(screen.gridView(3)!!.focused)

      screen.active = true
      assertTrue(screen.gridView(3)!!.focused)
    }
  }

  private fun render(shape: CursorShape, focused: Boolean): Bitmap {
    val grid = CellGrid(3, 1).apply {
      setCursorShape(shape)
      setCursor(0, 1)
    }
    lateinit var bitmap: Bitmap
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val view = TerminalGridView(context, grid, font).apply {
        paddingPx = 0f
        cursorColor = Color.WHITE
        this.focused = focused
      }
      val w = view.contentWidth
      val h = view.contentHeight
      view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
      view.layout(0, 0, w, h)
      bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      Canvas(bitmap).apply { drawColor(Color.BLACK) }.also(view::draw)
    }
    return bitmap
  }
}
