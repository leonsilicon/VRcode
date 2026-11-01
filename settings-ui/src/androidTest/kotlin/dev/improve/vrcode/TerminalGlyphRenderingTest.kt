package dev.improve.vrcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.cell.grid.Attr
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.TerminalGridView
import org.junit.Assert.*
import org.junit.Test

/**
 * Symbols that terminal apps print (Claude Code's `⏵⏵` and `⏸`, spinners, Braille graphs, sextants)
 * render as real glyphs, as text rather than color emoji, and inside their own cells. None of them
 * is in JetBrains Mono, and Android's system fonts (stock and Horizon OS alike) lack several.
 */
class TerminalGlyphRenderingTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val font = TerminalFont(context, 40f)

  @Test fun terminalSymbolsHaveGlyphs() {
    val tofu = SYMBOLS.filter { cp -> font.glyph(cp).let { it == null || it.id == 0 } }
    assertTrue("no glyph (tofu) for ${tofu.joinToString { label(it) }}", tofu.isEmpty())
  }

  @Test fun symbolsDrawAsTextInsideTheirCells() {
    // Each symbol alone in the middle row, with an empty cell to its right and empty rows around it.
    val grid = CellGrid(SYMBOLS.size * 2, 3).apply { setCursorVisible(false) }
    SYMBOLS.forEachIndexed { k, cp -> grid.put(1, k * 2, cp, fg = WHITE) }
    val bitmap = render(grid)
    val problems = SYMBOLS.withIndex().flatMap { (k, cp) ->
      val col = k * 2
      listOfNotNull(
        "${label(cp)} drew nothing".takeIf { ink(bitmap, col, 1) == 0 },
        "${label(cp)} spilled into the next cell".takeIf { ink(bitmap, col + 1, 1) > 0 },
        "${label(cp)} spilled into the row above".takeIf { ink(bitmap, col, 0) > 0 },
        "${label(cp)} spilled into the row below".takeIf { ink(bitmap, col, 2) > 0 },
        "${label(cp)} drew as color emoji".takeIf { colored(bitmap, col, 1) > 0 },
      )
    }
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }

  /** Fitting a full-width CJK `⎿` into one cell must move it, not shrink it to a speck. */
  @Test fun cornerConnectorKeepsItsHeight() {
    val grid = CellGrid(1, 1).apply {
      setCursorVisible(false)
      put(0, 0, 0x23BF, fg = WHITE)
    }
    val bitmap = render(grid)
    val inkRows = (0 until font.cellHeight.toInt()).count { y ->
      (0 until font.cellWidth.toInt()).any { x -> Color.red(bitmap.getPixel(x, y)) > 64 }
    }
    assertTrue("⎿ is $inkRows of ${font.cellHeight.toInt()} px tall", inkRows >= font.cellHeight * 0.6f)
  }

  /** Sextants fill exact thirds of the cell, edge to edge, so sextant art tiles without gaps. */
  @Test fun sextantsFillTheirThirdsOfTheCell() {
    // 🬀 is the top-left part only; 🬻 is every part but the top-left.
    val grid = CellGrid(2, 1).apply {
      setCursorVisible(false)
      put(0, 0, 0x1FB00, fg = WHITE)
      put(0, 1, 0x1FB3B, fg = WHITE)
    }
    val bitmap = render(grid)
    val w = font.cellWidth.toInt()
    val h = font.cellHeight.toInt()
    fun lit(col: Int, x: Int, y: Int) = Color.red(bitmap.getPixel(col * w + x, y)) > 128
    for (part in 0 until 6) {
      val x = if (part % 2 == 0) w / 4 else 3 * w / 4
      val y = h * (2 * (part / 2) + 1) / 6
      assertEquals("🬀 part ${part + 1}", part == 0, lit(0, x, y))
      assertEquals("🬻 part ${part + 1}", part != 0, lit(1, x, y))
    }
    // 🬻's bottom and right edges are lit to the last pixel.
    assertTrue((0 until w).all { lit(1, it, h - 1) })
    assertTrue((h / 3 until h).all { lit(1, w - 1, it) })
  }

  /** Powerline caps from the icon font span the full cell height, to meet the segment backgrounds. */
  @Test fun powerlineCapFillsTheCellHeight() {
    val grid = CellGrid(1, 1).apply {
      setCursorVisible(false)
      put(0, 0, 0xE0B4, fg = WHITE) // right half circle, flat side on the left edge
    }
    val bitmap = render(grid)
    val h = font.cellHeight.toInt()
    // At its natural size the cap would cover about three quarters of the cell height.
    val lit = (0 until h).filter { y -> (0..2).any { x -> Color.red(bitmap.getPixel(x, y)) > 32 } }
    assertTrue("U+E0B4 spans rows ${lit.firstOrNull()}..${lit.lastOrNull()} of 0..${h - 1}", lit.first() <= 1 && lit.last() >= h - 2)
  }

  @Test fun wideEmojiStaysColorAndInsideItsTwoCells() {
    val grid = CellGrid(3, 1).apply {
      setCursorVisible(false)
      put(0, 0, 0x1F642, fg = WHITE)
      put(0, 1, ' '.code, attrs = Attr.WIDE_SPACER)
    }
    val bitmap = render(grid)
    assertTrue("emoji lost its color", colored(bitmap, 0, 0) + colored(bitmap, 1, 0) > 0)
    assertEquals("emoji spilled past its two cells", 0, ink(bitmap, 2, 0))
  }

  /** Claude Code's status lines as the headset shows them; saved to the test app's `screenshots`. */
  @Test fun rendersClaudeCodeStatusLines() {
    val lines = listOf(
      "⏵⏵ bypass permissions on (shift+tab to cycle)",
      "⏸ plan mode on (shift+tab to cycle)",
      "⏺ Bash(ls)  ⎿  done  ✻ Thinking… ✢✳✶✽",
      "⣿⠿⡇ ⮕ ※ ◉ ✔ ✘ ⏎ ⧉ 🬀🬻🬋🬎",
      // Nerd Font icons: folder, Git branch, GitHub, Material git (U+F02A2, a surrogate pair), Powerline caps.
      "\uF07B src  \uE725 main  \uF113 \uDB80\uDEA2 \uE0B6\uE0B4",
    )
    val grid = CellGrid(lines.maxOf { it.codePointCount(0, it.length) }, lines.size).apply { setCursorVisible(false) }
    lines.forEachIndexed { row, line -> grid.write(row, 0, line, fg = 0xFFD75F5F.toInt()) }
    val bitmap = render(grid)
    val directory = java.io.File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    java.io.File(directory, "terminal-glyphs.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    for ((row, line) in lines.withIndex()) {
      line.codePoints().toArray().forEachIndexed { col, cp ->
        if (cp != ' '.code) assertTrue("${label(cp)} at $row:$col drew nothing", ink(bitmap, col, row) > 0)
      }
    }
  }

  private fun render(grid: CellGrid): Bitmap {
    lateinit var bitmap: Bitmap
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val view = TerminalGridView(context, grid, font).apply { paddingPx = 0f }
      val w = view.contentWidth
      val h = view.contentHeight
      view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
      view.layout(0, 0, w, h)
      bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      Canvas(bitmap).apply { drawColor(Color.BLACK) }.also(view::draw)
    }
    return bitmap
  }

  /** Pixels in a cell matching [test]. */
  private fun count(bitmap: Bitmap, col: Int, row: Int, test: (Int) -> Boolean): Int {
    val x0 = (col * font.cellWidth).toInt()
    val y0 = (row * font.cellHeight).toInt()
    var n = 0
    for (y in y0 until y0 + font.cellHeight.toInt()) for (x in x0 until x0 + font.cellWidth.toInt()) {
      if (test(bitmap.getPixel(x, y))) n++
    }
    return n
  }

  /** Clearly visible pixels (faint antialiasing at a shared edge does not count). */
  private fun ink(bitmap: Bitmap, col: Int, row: Int): Int =
    count(bitmap, col, row) { maxOf(Color.red(it), Color.green(it), Color.blue(it)) > 64 }

  /** Saturated pixels: white text on black antialiases to grays, so these come from a color font. */
  private fun colored(bitmap: Bitmap, col: Int, row: Int): Int =
    count(bitmap, col, row) {
      val r = Color.red(it)
      val g = Color.green(it)
      val b = Color.blue(it)
      maxOf(r, g, b) - minOf(r, g, b) > 48
    }

  private fun label(cp: Int) = "U+%04X %s".format(cp, String(Character.toChars(cp)))

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()

    val SYMBOLS = intArrayOf(
      0x23F5, 0x23F4, // ⏵ ⏴ (Claude Code's bypass-permissions mode)
      0x23F8, 0x23FA, // ⏸ ⏺ (plan mode; emoji-capable, but text by default)
      0x23BF, 0x23CE, 0x29C9, 0x21BB, 0x203B, // ⎿ ⏎ ⧉ ↻ ※
      0x2722, 0x2733, 0x2736, 0x273B, 0x273D, // ✢ ✳ ✶ ✻ ✽ (spinner)
      0x25C9, 0x2714, 0x2718, 0x2B95, // ◉ ✔ ✘ ⮕
      0x283F, 0x28FF, // ⠿ ⣿ (Braille spinners and graphs)
      0x1FB00, 0x1FB3B, // 🬀 🬻 (sextants)
      // Nerd Font icons: Powerline round cap, folder, Git branch, GitHub, Material git.
      0xE0B4, 0xF07B, 0xE725, 0xF113, 0xF02A2,
    ).toList()
  }
}
