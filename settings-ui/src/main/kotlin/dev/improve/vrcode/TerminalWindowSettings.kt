package dev.improve.vrcode

/** Overrides belong to one mounted terminal. Null continues to follow the app preference. */
data class TerminalWindowSettings(
  val style: WindowStyle? = null,
  val theme: TerminalTheme? = null,
  val fontSize: Float? = null,
  val cursor: TerminalCursor? = null,
) {
  fun sanitized() = copy(fontSize = fontSize?.takeIf { it.isFinite() }
    ?.coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE))

  fun resolve(defaults: AppSettings): AppSettings = defaults.copy(
    windowStyle = style ?: defaults.windowStyle,
    terminalTheme = theme ?: defaults.terminalTheme,
    terminalFontSize = sanitized().fontSize ?: defaults.terminalFontSize,
    terminalCursor = cursor ?: defaults.terminalCursor,
  )
}
