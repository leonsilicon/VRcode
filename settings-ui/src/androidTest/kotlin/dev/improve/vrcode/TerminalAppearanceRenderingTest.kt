package dev.improve.vrcode

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
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
import dev.improve.xmux.terminal.grid.TerminalGridView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalAppearanceRenderingTest {
  @get:Rule val compose = createComposeRule()

  @Test fun localThemeSurvivesRemoteUpdatesPreservesExplicitColorsAndRestoresMac() {
    val explicitFg = 0xFFFF4444.toInt()
    val explicitBg = 0xFF114477.toInt()
    val remoteFg = 0xFF99AA99.toInt()
    val remoteBg = 0xFF112211.toInt()
    val grid = CellGrid(4, 2).apply {
      defaultFg = remoteFg
      defaultBg = remoteBg
      put(0, 0, 'M'.code)
      put(0, 1, 'M'.code, fg = explicitFg)
      put(0, 2, ' '.code, attrs = Attr.INVERSE)
      put(0, 3, 'M'.code, attrs = Attr.INVISIBLE)
      put(1, 0, ' '.code, bg = explicitBg)
      setCursor(1, 3)
    }
    var settings by mutableStateOf(AppSettings(terminalTheme = TerminalTheme.CatppuccinLatte, terminalCursor = TerminalCursor.Bar))
    lateinit var view: TerminalGridView
    lateinit var font: TerminalFont
    compose.setContent {
      val context = LocalContext.current
      font = remember { TerminalFont(context, 25f) }
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize((font.cellWidth * 4).dp, (font.cellHeight * 2).dp).testTag("rendered-grid")) {
          AndroidView(factory = {
            TerminalGridView(it, grid, font).apply { paddingPx = 0f; view = this }
          }, update = { it.appearance = settings.gridAppearance() })
        }
      }
    }
    fun pixel(bitmap: Bitmap, col: Int, row: Int, fraction: Float = 0.5f): Int =
      bitmap.getPixel((font.cellWidth * (col + fraction)).toInt(), (font.cellHeight * (row + 0.5f)).toInt())
    fun cellContains(bitmap: Bitmap, col: Int, color: Int): Boolean {
      for (y in 0 until font.cellHeight.toInt()) for (x in (col * font.cellWidth).toInt() until ((col + 1) * font.cellWidth).toInt()) {
        if (bitmap.getPixel(x, y) == color) return true
      }
      return false
    }
    fun capture() = compose.onNodeWithTag("rendered-grid").captureToImage().asAndroidBitmap()

    val themed = capture()
    val theme = TerminalTheme.CatppuccinLatte
    assertEquals(theme.background.toInt(), pixel(themed, 1, 1))
    assertEquals(explicitBg, pixel(themed, 0, 1))
    assertEquals(theme.foreground.toInt(), pixel(themed, 2, 0))
    assertEquals(theme.background.toInt(), pixel(themed, 3, 0)) // Invisible text stays hidden.
    assertTrue(cellContains(themed, 0, theme.foreground.toInt()))
    assertTrue(cellContains(themed, 1, explicitFg))
    assertEquals(theme.cursor.toInt(), pixel(themed, 3, 1, 0.05f))
    assertEquals(theme.background.toInt(), pixel(themed, 3, 1))
    compose.runOnIdle {
      assertEquals(remoteFg, grid.defaultFg)
      assertEquals(remoteBg, grid.defaultBg)
      grid.defaultFg = 0xFFAA99AA.toInt()
      grid.defaultBg = 0xFF221122.toInt()
      view.refreshIfDirty()
    }
    assertEquals(theme.background.toInt(), pixel(capture(), 1, 1))
    compose.runOnIdle { settings = AppSettings() }
    val restored = capture()
    assertEquals(0xFF221122.toInt(), pixel(restored, 1, 1))
    assertTrue(cellContains(restored, 0, 0xFFAA99AA.toInt()))
    assertTrue(cellContains(restored, 1, explicitFg))
    assertEquals(explicitBg, pixel(restored, 0, 1))
  }
}
