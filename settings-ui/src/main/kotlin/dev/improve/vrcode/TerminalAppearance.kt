package dev.improve.vrcode

import dev.improve.xmux.cell.grid.CursorShape
import dev.improve.xmux.terminal.grid.GridAppearance
import dev.improve.xmux.window.frame.WindowChrome
import kotlin.math.roundToLong

/** Default colors from the MIT-licensed iTerm2 Color Schemes collection used by Ghostty.
 * See docs/terminal-appearance.md and assets/terminal-themes/LICENSE. Explicit app colors are kept.
 */
enum class TerminalTheme(
  val storageValue: String,
  val label: String,
  val detail: String,
  val background: Long,
  val foreground: Long,
  val cursor: Long,
  val accent: Long,
) {
  Mac("mac", "Follow Mac", "Use your Mac's terminal colors", 0xFF1E1E1E, 0xFFD4D4D4, 0xFFD4D4D4, 0xFFA9C7FF),
  CatppuccinMocha("catppuccin-mocha", "Catppuccin Mocha", "Soft pastels · dark", 0xFF1E1E2E, 0xFFCDD6F4, 0xFFF5E0DC, 0xFF89B4FA),
  Dracula("dracula", "Dracula", "Purple tones · dark", 0xFF282A36, 0xFFF8F8F2, 0xFFF8F8F2, 0xFFBD93F9),
  Nord("nord", "Nord", "Cool blues · dark", 0xFF2E3440, 0xFFD8DEE9, 0xFFECEFF4, 0xFF81A1C1),
  TokyoNight("tokyo-night", "Tokyo Night", "Deep indigo · dark", 0xFF1A1B26, 0xFFC0CAF5, 0xFFC0CAF5, 0xFF7AA2F7),
  GruvboxDark("gruvbox-dark", "Gruvbox Dark", "Warm neutrals · dark", 0xFF282828, 0xFFEBDBB2, 0xFFEBDBB2, 0xFF458588),
  SolarizedDark("solarized-dark", "Solarized Dark", "Muted teal · dark", 0xFF002B36, 0xFF839496, 0xFF839496, 0xFF268BD2),
  CatppuccinLatte("catppuccin-latte", "Catppuccin Latte", "Soft pastels · light", 0xFFEFF1F5, 0xFF4C4F69, 0xFFDC8A78, 0xFF1E66F5),
  SolarizedLight("solarized-light", "Solarized Light", "Warm paper · light", 0xFFFDF6E3, 0xFF657B83, 0xFF657B83, 0xFF268BD2);

  val raisedBackground: Long
    get() {
      var color = 0xFF000000L
      for (shift in listOf(16, 8, 0)) {
        val bg = (background shr shift) and 255
        val fg = (foreground shr shift) and 255
        color = color or ((bg + (fg - bg) * 0.15f).toLong() shl shift)
      }
      return color
    }

  companion object {
    fun fromStorage(value: String?): TerminalTheme = entries.firstOrNull { it.storageValue == value } ?: Mac
  }
}

enum class TerminalCursor(val storageValue: String, val label: String) {
  Terminal("terminal", "Follow app"), Block("block", "Block"), Bar("bar", "Bar"), Underline("underline", "Underline");

  companion object {
    fun fromStorage(value: String?): TerminalCursor = entries.firstOrNull { it.storageValue == value } ?: Terminal
  }
}

fun AppSettings.gridAppearance(): GridAppearance = GridAppearance(
  foreground = terminalTheme.takeUnless { it == TerminalTheme.Mac }?.foreground?.toInt(),
  background = terminalTheme.takeUnless { it == TerminalTheme.Mac }?.background?.toInt(),
  cursorColor = terminalTheme.takeUnless { it == TerminalTheme.Mac }?.cursor?.toInt(),
  cursorShape = when (terminalCursor) {
    TerminalCursor.Terminal -> null
    TerminalCursor.Block -> CursorShape.Block
    TerminalCursor.Bar -> CursorShape.Bar
    TerminalCursor.Underline -> CursorShape.Underline
  },
)

/** [color] (ARGB) with its alpha scaled by [opacity]. */
fun withOpacity(color: Long, opacity: Float): Long =
  ((((color ushr 24) and 0xFF) * opacity.coerceIn(0f, 1f)).roundToLong() shl 24) or (color and 0xFFFFFF)

/**
 * The header and body colors of [theme] with backgrounds at [opacity], keeping all window actions
 * and slots. Follow Mac uses xmux's default window colors, whatever colors this chrome has (a
 * terminal window's own chrome leaves its frame clear, since its screen view paints the body).
 */
fun WindowChrome.withTerminalTheme(theme: TerminalTheme, opacity: Float = 1f): WindowChrome {
  val mac = theme == TerminalTheme.Mac
  val defaults = WindowChrome.Default
  return WindowChrome(
    headerHeightDp = headerHeightDp, cornerRadiusDp = cornerRadiusDp,
    bodyColor = withOpacity(if (mac) defaults.bodyColor else theme.background, opacity),
    headerColor = withOpacity(if (mac) defaults.headerColor else theme.background, opacity),
    titleColor = if (mac) defaults.titleColor else theme.foreground,
    onClose = onClose, onMinimize = onMinimize, onZoom = onZoom,
    leadingSlot = leadingSlot, trailingSlot = trailingSlot, header = header, overlay = overlay, outline = outline,
  )
}
