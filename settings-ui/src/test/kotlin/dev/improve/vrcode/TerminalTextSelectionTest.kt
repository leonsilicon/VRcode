package dev.improve.vrcode

import dev.improve.xmux.cell.grid.Attr
import dev.improve.xmux.cell.grid.CellGrid
import org.junit.Assert.*
import org.junit.Test

class TerminalTextSelectionTest {
  private fun selection(row: Int, col: Int, endRow: Int, endCol: Int) = TerminalTextSelection(
    TerminalTextSelection.Cell(row, col), TerminalTextSelection.Cell(endRow, endCol))

  @Test fun forwardAndBackwardMultilineSelectionsIncludeEndpointsAndTrimRowPadding() {
    val grid = CellGrid(10, 3).apply {
      write(0, 0, "hello")
      write(1, 0, "world")
      write(2, 0, "terminal")
    }
    assertEquals("llo\nworld\nter", selection(0, 2, 2, 2).text(grid))
    assertEquals(selection(0, 2, 2, 2).spans(grid), selection(2, 2, 0, 2).spans(grid))
    assertEquals("llo\nworld\nter", selection(2, 2, 0, 2).text(grid))
    assertEquals("orl", selection(1, 3, 1, 1).text(grid))
  }

  @Test fun wideGlyphHalvesCombiningClustersAndSupplementaryCharactersStayWhole() {
    val grid = CellGrid(6, 1).apply {
      put(0, 0, '界'.code)
      put(0, 1, ' '.code, attrs = Attr.WIDE_SPACER)
      putCluster(0, 2, "e\u0301")
      put(0, 3, 0x1F600)
      put(0, 4, ' '.code, attrs = Attr.WIDE_SPACER)
      put(0, 5, '!'.code)
    }
    assertEquals("界", selection(0, 1, 0, 1).text(grid))
    assertEquals(listOf(TerminalTextSelection.Span(0, 0, 2)), selection(0, 0, 0, 0).spans(grid))
    assertEquals("界e\u0301😀", selection(0, 1, 0, 4).text(grid))
    assertEquals("e\u0301😀!", selection(0, 5, 0, 2).text(grid))
  }

  @Test fun rangeClipsToGridWithoutMutatingStreamedCellsOrStyles() {
    val grid = CellGrid(3, 2).apply {
      write(0, 0, "abc", fg = 0xFFFF0000.toInt(), attrs = Attr.BOLD)
      write(1, 0, "def")
      clearDirty()
    }
    val before = grid.attrs.copyOf()
    assertEquals("abc\ndef", selection(-9, -4, 99, 99).text(grid))
    assertArrayEquals(before, grid.attrs)
    assertFalse(grid.anyDirty())
  }
}
