package dev.improve.vrcode

import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.terminal.grid.GridAppearance
import dev.improve.xmux.terminal.grid.TerminalGridView
import java.util.WeakHashMap

/** Apply local display overrides after topology updates, including newly created panes/views. */
class TerminalAppearanceController {
  private val styledScreens = WeakHashMap<ScreenView, TerminalTheme>()
  private val appearances = mutableMapOf<Pair<TerminalTheme, TerminalCursor>, GridAppearance>()

  fun update(windows: List<ScreenWindow>, settingsFor: (ScreenWindow) -> AppSettings) {
    for (window in windows) {
      val settings = settingsFor(window)
      val theme = settings.terminalTheme
      val appearance = appearances.getOrPut(theme to settings.terminalCursor) { settings.gridAppearance() }
      window.view?.let { it.appearance = appearance }
      val screen = window.screenView ?: continue
      if (styledScreens[screen] != theme) {
        val mac = theme == TerminalTheme.Mac
        screen.bodyColor = theme.background.toInt()
        screen.tabColor = if (mac) 0xFF262626.toInt() else theme.background.toInt()
        screen.activeTabColor = if (mac) 0xFF333333.toInt() else theme.raisedBackground.toInt()
        screen.dividerColor = if (mac) 0xFF3A3A3A.toInt() else theme.raisedBackground.toInt()
        screen.labelColor = if (mac) 0xFFB0B0B0.toInt() else theme.foreground.toInt()
        screen.focusColor = if (mac) 0xFF4A90D9.toInt() else theme.accent.toInt()
        screen.setBackgroundColor(screen.bodyColor)
        screen.invalidate()
        styledScreens[screen] = theme
      }
      for (i in 0 until screen.childCount) {
        (screen.getChildAt(i) as? TerminalGridView)?.appearance = appearance
      }
    }
  }
}
