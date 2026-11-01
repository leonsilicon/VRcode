package dev.improve.vrcode

import dev.improve.xmux.cell.grid.CursorShape
import org.junit.Assert.*
import org.junit.Test

class TerminalAppearanceTest {
  @Test fun oldOrUnknownPreferencesKeepTheMacAppearance() {
    assertEquals(TerminalTheme.Mac, TerminalTheme.fromStorage(null))
    assertEquals(TerminalTheme.Mac, TerminalTheme.fromStorage("removed-theme"))
    assertEquals(TerminalCursor.Terminal, TerminalCursor.fromStorage(null))
    assertEquals(TerminalCursor.Terminal, TerminalCursor.fromStorage("removed-cursor"))
    val appearance = AppSettings().gridAppearance()
    assertNull(appearance.foreground)
    assertNull(appearance.background)
    assertNull(appearance.cursorColor)
    assertNull(appearance.cursorShape)
  }

  @Test fun invalidFontSizesCannotProduceUnusablePanels() {
    for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
      assertEquals(AppSettings.DEFAULT_FONT_SIZE, AppSettings(terminalFontSize = invalid).sanitized().terminalFontSize)
    }
    assertEquals(AppSettings.MIN_FONT_SIZE, AppSettings(terminalFontSize = -1f).sanitized().terminalFontSize)
    assertEquals(AppSettings.MAX_FONT_SIZE, AppSettings(terminalFontSize = 500f).sanitized().terminalFontSize)
    val chosen = AppSettings(terminalTheme = TerminalTheme.CatppuccinLatte, terminalFontSize = 32f, terminalCursor = TerminalCursor.Bar)
    assertEquals(chosen, chosen.sanitized())
    assertEquals(CursorShape.Bar, chosen.gridAppearance().cursorShape)
    assertEquals(0xFFEFF1F5.toInt(), chosen.gridAppearance().background)
  }
}
