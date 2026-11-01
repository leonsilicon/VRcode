package dev.improve.vrcode

import org.junit.Assert.*
import org.junit.Test

class TerminalWindowSettingsTest {
  @Test fun windowOverridesStayIndependentAndUnsetOptionsFollowAppChanges() {
    val defaults = AppSettings()
    val first = TerminalWindowSettings(theme = TerminalTheme.Dracula, fontSize = 32f)
    val second = TerminalWindowSettings(cursor = TerminalCursor.Bar)
    assertEquals(TerminalTheme.Dracula, first.resolve(defaults).terminalTheme)
    assertEquals(TerminalTheme.Mac, second.resolve(defaults).terminalTheme)
    assertEquals(32f, first.resolve(defaults).terminalFontSize)
    assertEquals(AppSettings.DEFAULT_FONT_SIZE, second.resolve(defaults).terminalFontSize)
    val changed = defaults.copy(windowStyle = WindowStyle.Windows, terminalTheme = TerminalTheme.Nord, terminalCursor = TerminalCursor.Block)
    assertEquals(TerminalTheme.Dracula, first.resolve(changed).terminalTheme)
    assertEquals(WindowStyle.Windows, first.resolve(changed).windowStyle)
    assertEquals(TerminalCursor.Bar, second.resolve(changed).terminalCursor)
    assertEquals(TerminalTheme.Nord, second.resolve(changed).terminalTheme)
    assertEquals(changed, TerminalWindowSettings().resolve(changed))
    assertEquals(AppSettings(), defaults)
  }

  @Test fun invalidWindowFontSizesCannotBreakPanelSizing() {
    assertNull(TerminalWindowSettings(fontSize = Float.NaN).sanitized().fontSize)
    assertNull(TerminalWindowSettings(fontSize = Float.POSITIVE_INFINITY).sanitized().fontSize)
    assertEquals(AppSettings.MIN_FONT_SIZE, TerminalWindowSettings(fontSize = 0f).sanitized().fontSize)
    assertEquals(AppSettings.MAX_FONT_SIZE, TerminalWindowSettings(fontSize = 100f).sanitized().fontSize)
  }
}
