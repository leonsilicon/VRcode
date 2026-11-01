package dev.improve.vrcode

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.improve.xmux.cell.grid.Attr
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.GridAppearance
import dev.improve.xmux.terminal.grid.TerminalGridView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * On a hardware canvas `TerminalGridView` records each row into its own render nodes and, on a
 * change, re-records only the rows the grid marked dirty. A grid redrawn that way must look exactly
 * like a new view of the same grid drawn from scratch, and the update must re-record less.
 */
class TerminalRowCacheRenderingTest {
  @get:Rule val compose = createComposeRule()

  private fun initial(grid: CellGrid) {
    grid.write(0, 0, "user@mac ~/vrcode % ls -la", fg = 0xFF5FD75F.toInt())
    grid.write(1, 0, "drwxr-xr-x  12 user  staff   384 Nov  1 app")
    grid.write(2, 0, "-rw-r--r--   1 user  staff  1234 Nov  1 PLAN.md", fg = 0xFFD7AF5F.toInt())
    grid.fill(3, 0, 20, bg = 0xFF303060.toInt())
    grid.write(3, 2, "status: building", bg = 0xFF303060.toInt(), attrs = Attr.BOLD)
    grid.write(4, 0, "gypq descenders", attrs = Attr.UNDERLINE)
    grid.fill(5, 0, COLS, bg = 0xFF602020.toInt())
    grid.write(6, 0, "⠋ Thinking… ⏵⏵ accept edits", fg = 0xFFAF87FF.toInt())
    grid.write(7, 0, "inverse", attrs = Attr.INVERSE)
    grid.setCursor(0, 27)
  }

  /** What a busy terminal does in one frame: a spinner ticks, a line prints, the cursor moves. */
  private fun update(grid: CellGrid) {
    grid.write(6, 0, "⠙", fg = 0xFFAF87FF.toInt())
    grid.write(2, 0, "-rw-r--r--   1 user  staff  5678 Nov  1 README.md", fg = 0xFF87D7FF.toInt())
    grid.write(5, 4, "error: fake", fg = 0xFFFFFFFF.toInt(), bg = 0xFF602020.toInt(), attrs = Attr.STRIKE)
    grid.setCursor(7, 10)
  }

  @Test fun redrawingOnlyChangedRowsMatchesAFullRedraw() {
    val live = CellGrid(COLS, ROWS).also(::initial)
    var appearance by mutableStateOf(GridAppearance())
    var freshGrid by mutableStateOf<CellGrid?>(null)
    var freshKey by mutableIntStateOf(0)
    lateinit var view: TerminalGridView
    lateinit var freshView: TerminalGridView
    lateinit var font: TerminalFont
    compose.setContent {
      val context = LocalContext.current
      font = remember { TerminalFont(context, 25f) }
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        val size = Modifier.requiredSize((font.cellWidth * COLS).dp, (font.cellHeight * ROWS).dp)
        Column {
          Box(size.testTag("live")) {
            AndroidView(factory = { TerminalGridView(it, live, font).apply { paddingPx = 0f; view = this } },
              update = { it.appearance = appearance })
          }
          freshGrid?.let { grid ->
            key(freshKey) {
              Box(size.testTag("fresh")) {
                AndroidView(factory = { TerminalGridView(it, grid, font).apply { paddingPx = 0f; freshView = this } },
                  update = { it.appearance = appearance })
              }
            }
          }
        }
      }
    }
    fun capture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    compose.runOnIdle {
      cellW = font.cellWidth
      cellH = font.cellHeight
    }
    fun freshOf(block: (CellGrid) -> Unit) = compose.runOnIdle {
      freshGrid = CellGrid(COLS, ROWS).also(block)
      freshKey++
    }

    val before = capture("live")
    compose.runOnIdle {
      update(live)
      view.refreshIfDirty()
    }
    val incremental = capture("live")
    var updateCalls = 0
    compose.runOnIdle { updateCalls = view.lastDrawCalls }
    assertFalse("the update changed nothing on screen", same(before, incremental))
    freshOf { initial(it); update(it) }
    assertSame("incrementally redrawn", capture("fresh"), incremental)
    // A software canvas would redraw every row; fewer calls than a full draw proves the row cache ran.
    compose.runOnIdle {
      assertTrue("the update recorded $updateCalls draw calls, a full draw ${freshView.lastDrawCalls}",
        updateCalls < freshView.lastDrawCalls)
    }

    // A local theme changes no grid row, yet every row's colors.
    compose.runOnIdle { appearance = GridAppearance(foreground = 0xFF00C000.toInt(), background = 0xFF000060.toInt()) }
    val themed = capture("live")
    freshOf { initial(it); update(it) }
    assertSame("after a theme change", capture("fresh"), themed)
    assertFalse(same(incremental, themed))
  }

  private fun same(a: Bitmap, b: Bitmap): Boolean = a.width == b.width && a.height == b.height && differences(a, b) == 0

  /**
   * Pixels where some channel differs by more than [tolerance]. The GPU may round a blend of an
   * antialiased glyph edge one level differently between two draws of the same display list; a
   * stale or miscoloured row changes whole glyphs by far more.
   */
  private fun differences(a: Bitmap, b: Bitmap, tolerance: Int = 0): Int {
    var n = 0
    for (y in 0 until minOf(a.height, b.height)) for (x in 0 until minOf(a.width, b.width)) if (differs(a.getPixel(x, y), b.getPixel(x, y), tolerance)) n++
    return n
  }

  private fun differs(p: Int, q: Int, tolerance: Int): Boolean =
    (0 until 32 step 8).any { shift -> kotlin.math.abs(((p ushr shift) and 0xFF) - ((q ushr shift) and 0xFF)) > tolerance }

  private fun assertSame(what: String, expected: Bitmap, actual: Bitmap) {
    assertEquals("$what: size", expected.width to expected.height, actual.width to actual.height)
    val n = differences(expected, actual, ROUNDING)
    if (n == 0) return
    val first = (0 until expected.height).asSequence().flatMap { y -> (0 until expected.width).asSequence().map { x -> x to y } }
      .first { (x, y) -> differs(expected.getPixel(x, y), actual.getPixel(x, y), ROUNDING) }
    val (x, y) = first
    fail("$what: $n pixels differ from a fresh view, first at ($x, $y) = cell (${(x / cellW).toInt()}, ${(y / cellH).toInt()}): " +
      "fresh %08x, redrawn %08x".format(expected.getPixel(x, y), actual.getPixel(x, y)))
  }

  private var cellW = 1f
  private var cellH = 1f

  private companion object {
    const val COLS = 48
    const val ROWS = 8
    /** Blend rounding allowed per channel, in 8-bit levels. */
    const val ROUNDING = 2
  }
}
