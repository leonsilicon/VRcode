package dev.improve.vrcode

import dev.improve.xmux.cell.grid.Attr
import dev.improve.xmux.cell.grid.CellGrid

/** A local, inclusive range in the displayed terminal, independent of PTY mouse reporting. */
data class TerminalTextSelection(val anchor: Cell, val extent: Cell) {
  data class Cell(val row: Int, val col: Int)
  data class Span(val row: Int, val startCol: Int, val endCol: Int)

  fun spans(grid: CellGrid): List<Span> {
    fun Cell.clamped() = Cell(row.coerceIn(0, grid.rows - 1), col.coerceIn(0, grid.cols - 1))
    val a = anchor.clamped()
    val b = extent.clamped()
    val forward = a.row < b.row || a.row == b.row && a.col <= b.col
    val start = if (forward) a else b
    val end = if (forward) b else a
    return (start.row..end.row).map { row ->
      var from = if (row == start.row) start.col else 0
      var to = if (row == end.row) end.col + 1 else grid.cols
      // Either half of a wide glyph selects the whole grapheme.
      while (from > 0 && grid.attrs[grid.index(row, from)] and Attr.WIDE_SPACER != 0) from--
      while (to < grid.cols && grid.attrs[grid.index(row, to)] and Attr.WIDE_SPACER != 0) to++
      Span(row, from, to)
    }
  }

  fun text(grid: CellGrid): String = spans(grid).joinToString("\n") { span ->
    buildString {
      for (col in span.startCol until span.endCol) append(grid.cellText(span.row, col))
    }.trimEnd(' ')
  }
}
