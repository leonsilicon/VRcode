package dev.improve.vrcode

import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.terminal.grid.TerminalGridView
import java.util.WeakHashMap

/** Apply local display overrides after topology updates, including newly created panes/views. */
class TerminalAppearanceController {
  private val styledScreens = WeakHashMap<ScreenView, TerminalTheme>()
  private var currentTheme = TerminalTheme.Mac
  private var currentCursor = TerminalCursor.Terminal
  private var appearance = AppSettings().gridAppearance()

  fun update(windows: List<ScreenWindow>, settings: AppSettings) {
    val theme = settings.terminalTheme
    if (currentTheme != theme || currentCursor != settings.terminalCursor) {
      currentTheme = theme
      currentCursor = settings.terminalCursor
      appearance = settings.gridAppearance()
    }
    for (window in windows) {
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
