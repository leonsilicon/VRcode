package dev.improve.vrcode

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.terminal.grid.TerminalGridView
import kotlin.math.floor

/**
 * ISDK index-pinch events scroll the terminal or select its displayed text, per [drag]. Defer a
 * click until release: once the hand drags to another cell, no click reaches the terminal program.
 *
 * [PinchDrag.Scroll] sends wheel rows from the cell the pinch started on ([onScroll], negative
 * scrolls up) so the text follows the hand, like a touchscreen: dragging down a row scrolls up a
 * row. The drag keeps counting rows past the pane's edges.
 *
 * [PinchDrag.Select] selects locally. The remote mouse API only writes mouse reports to the PTY,
 * so it cannot supply a selection or its highlight; no mouse input is sent during the drag.
 */
class TerminalSelectionInput<T : Any>(
  private val screen: ScreenView,
  private val targetAt: (Int) -> T?,
  private val onFocus: (Int) -> Unit,
  private val onClick: (T, TerminalTextSelection.Cell) -> Unit,
  private val onCopy: (String) -> Unit,
  private val onScroll: (T, TerminalTextSelection.Cell, Int) -> Unit = { _, _, _ -> },
  private val onStart: () -> Unit = {},
) : AutoCloseable {
  var enabled = true
    set(value) {
      field = value
      if (!value) clear()
    }

  /** Read when a pinch starts; a held drag keeps the behavior it began with. */
  var drag: PinchDrag = PinchDrag.Scroll

  private inner class Capture(
    val target: T,
    val pane: Int,
    val view: TerminalGridView,
    val grid: CellGrid,
    val anchor: TerminalTextSelection.Cell,
    var pointerId: Int?,
    val drag: PinchDrag,
  ) {
    var extent = anchor
    var moved = false
    /** The unclamped row the hand is on, for scrolling; the anchor's row to begin with. */
    var scrollRow = anchor.row
    val overlay = SelectionOverlay(view)
  }

  private var capture: Capture? = null

  init {
    screen.setOnTouchListener { _, event -> touch(event) }
  }

  /** Called after topology updates as well as before events, including a tab switch or resize. */
  fun validate() {
    val c = capture ?: return
    if (targetAt(c.pane) != c.target || screen.gridView(c.pane) !== c.view ||
        c.view.grid !== c.grid || c.view.visibility != View.VISIBLE) clear()
  }

  fun cancel() {
    if (capture?.pointerId != null) clear()
  }

  fun clear() {
    capture?.let { it.view.overlay.remove(it.overlay) }
    capture = null
  }

  override fun close() {
    clear()
    screen.setOnTouchListener(null)
  }

  private fun move(c: Capture, x: Float, y: Float) {
    if (!x.isFinite() || !y.isFinite()) return
    // Retain the starting pane even over a divider, tab strip, or outside the panel.
    val col = floor((x - c.view.left - c.view.paddingPx) / c.view.font.cellWidth).toInt().coerceIn(0, c.grid.cols - 1)
    val unclamped = floor((y - c.view.top - c.view.paddingPx) / c.view.font.cellHeight).toInt()
    val cell = TerminalTextSelection.Cell(unclamped.coerceIn(0, c.grid.rows - 1), col)
    if (c.drag == PinchDrag.Scroll) {
      val rows = unclamped - c.scrollRow
      if (cell != c.extent || rows != 0) c.moved = true
      c.extent = cell
      if (rows == 0) return
      c.scrollRow = unclamped
      onScroll(c.target, c.anchor, -rows)
      return
    }
    if (cell == c.extent) return
    c.extent = cell
    c.moved = true
    c.overlay.selection = TerminalTextSelection(c.anchor, cell)
  }

  private fun touch(event: MotionEvent): Boolean {
    if (!enabled) { clear(); return true }
    validate()
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        clear()
        onStart()
        // The native tab listener owns tab activation. Its UP must not click the new terminal.
        if (screen.tabAt(event.x, event.y) != null) {
          screen.onTouchEvent(event)
          return true
        }
        val hit = screen.hit(event.x, event.y) ?: return true
        // Like a Mac window in the background, the first press only brings the window forward (and
        // focuses the pane under it); the terminal gets neither the click nor a selection.
        val background = !screen.active
        onFocus(hit.pane)
        screen.requestFocus()
        if (background || hit.row < 0 || hit.col < 0) return true
        val target = targetAt(hit.pane) ?: return true
        val view = screen.gridView(hit.pane) ?: return true
        capture = Capture(target, hit.pane, view, view.grid,
          TerminalTextSelection.Cell(hit.row, hit.col), event.getPointerId(0), drag).also {
          if (it.drag == PinchDrag.Select) {
            it.overlay.setBounds(0, 0, view.width, view.height)
            view.overlay.add(it.overlay)
          }
        }
      }
      MotionEvent.ACTION_MOVE -> {
        val c = capture ?: return true
        val index = c.pointerId?.let(event::findPointerIndex) ?: return true
        if (index < 0) clear() else move(c, event.getX(index), event.getY(index))
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
        val c = capture ?: return true
        val index = event.actionIndex
        if (event.getPointerId(index) != c.pointerId) return true
        move(c, event.getX(index), event.getY(index))
        c.pointerId = null
        if (c.drag == PinchDrag.Scroll) {
          clear()
          if (!c.moved) onClick(c.target, c.anchor)
        } else if (c.moved) {
          TerminalTextSelection(c.anchor, c.extent).text(c.grid).takeIf { it.isNotEmpty() }?.let(onCopy)
        } else {
          clear()
          onClick(c.target, c.anchor)
        }
      }
      MotionEvent.ACTION_CANCEL -> cancel()
    }
    return true
  }
}

/** ViewOverlay paints above streamed cell backgrounds without changing the shared grid. */
private class SelectionOverlay(private val view: TerminalGridView) : Drawable() {
  var selection: TerminalTextSelection? = null
    set(value) { field = value; invalidateSelf() }
  private val paint = Paint().apply { color = 0x887DC7FF.toInt() }

  override fun draw(canvas: Canvas) {
    val selection = selection ?: return
    val padding = view.paddingPx
    for (span in selection.spans(view.grid)) {
      val top = padding + span.row * view.font.cellHeight
      canvas.drawRect(padding + span.startCol * view.font.cellWidth, top,
        padding + span.endCol * view.font.cellWidth, top + view.font.cellHeight, paint)
    }
  }

  override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
  override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
  @Deprecated("Deprecated in Android")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
