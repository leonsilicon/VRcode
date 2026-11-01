package dev.improve.vrcode

import dev.improve.xmux.client.MouseAction
import dev.improve.xmux.client.MouseEvent

/** One captured left-button gesture. Changing panes never changes the recipient mid-drag. */
internal class MouseDrag<T : Any>(private val send: (T, MouseEvent) -> Unit) {
  data class Cell(val row: Int, val col: Int)
  var target: T? = null
    private set
  private var last: Cell? = null

  fun press(target: T, cell: Cell) {
    release()
    this.target = target
    last = cell
    send(target, MouseEvent(MouseAction.Press, 0, cell.row, cell.col))
  }

  fun move(cell: Cell) {
    val target = target ?: return
    if (cell == last) return
    last = cell
    send(target, MouseEvent(MouseAction.Move, 0, cell.row, cell.col))
  }

  /** Cancellation also releases the Mac's button, even if the pointer no longer hits a cell. */
  fun release(cell: Cell? = null) {
    val target = target ?: return
    // A quick drag can end before the SDK delivers a separate move at its final position.
    if (cell != null) move(cell)
    val end = cell ?: last!!
    this.target = null
    last = null
    send(target, MouseEvent(MouseAction.Release, 0, end.row, end.col))
  }
}
