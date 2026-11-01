package dev.improve.vrcode

import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.terminal.grid.GridAppearance
import dev.improve.xmux.terminal.grid.TerminalGridView
import java.util.WeakHashMap

/**
 * Apply local display overrides after topology updates, including newly created panes/views, and
 * mark which window is active: only [focusedScreen]'s focused pane shows a solid cursor and a pane
 * focus outline, as only the key window's focused split does on a Mac.
 */
class TerminalAppearanceController {
  private val styledScreens = WeakHashMap<ScreenView, TerminalTheme>()
  private val appearances = mutableMapOf<Pair<TerminalTheme, TerminalCursor>, GridAppearance>()

  fun update(windows: List<ScreenWindow>, focusedScreen: ScreenId?, settingsFor: (ScreenWindow) -> AppSettings) {
    for (window in windows) {
      val active = window.screen != null && window.screen == focusedScreen
      val settings = settingsFor(window)
      val theme = settings.terminalTheme
      val appearance = appearances.getOrPut(theme to settings.terminalCursor) { settings.gridAppearance() }
      window.view?.let { it.appearance = appearance; it.focused = active; it.backgroundOpacity = settings.windowOpacity }
      val screen = window.screenView ?: continue
      screen.active = active
      // The screen paints its body and passes the opacity to its panes; the window frame stays clear.
      screen.backgroundOpacity = settings.windowOpacity
      if (styledScreens[screen] != theme) {
        val mac = theme == TerminalTheme.Mac
        screen.bodyColor = theme.background.toInt()
        screen.tabColor = if (mac) 0xFF262626.toInt() else theme.background.toInt()
        screen.activeTabColor = if (mac) 0xFF333333.toInt() else theme.raisedBackground.toInt()
        screen.dividerColor = if (mac) 0xFF3A3A3A.toInt() else theme.raisedBackground.toInt()
        screen.labelColor = if (mac) 0xFFB0B0B0.toInt() else theme.foreground.toInt()
        screen.focusColor = if (mac) 0xFF4A90D9.toInt() else theme.accent.toInt()
        screen.invalidate()
        styledScreens[screen] = theme
      }
      for (i in 0 until screen.childCount) {
        (screen.getChildAt(i) as? TerminalGridView)?.appearance = appearance
      }
    }
  }
}
