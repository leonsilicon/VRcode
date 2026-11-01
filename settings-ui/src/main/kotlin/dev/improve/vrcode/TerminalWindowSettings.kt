package dev.improve.vrcode

/** Overrides belong to one mounted terminal. Null continues to follow the app preference. */
data class TerminalWindowSettings(
  val style: WindowStyle? = null,
  val theme: TerminalTheme? = null,
  val fontSize: Float? = null,
  val cursor: TerminalCursor? = null,
  val opacity: Float? = null,
) {
  fun sanitized() = copy(
    fontSize = fontSize?.takeIf { it.isFinite() }?.coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE),
    opacity = opacity?.let(AppSettings::sanitizeWindowOpacity),
  )

  fun resolve(defaults: AppSettings): AppSettings = sanitized().let { s ->
    defaults.copy(
      windowStyle = s.style ?: defaults.windowStyle,
      terminalTheme = s.theme ?: defaults.terminalTheme,
      terminalFontSize = s.fontSize ?: defaults.terminalFontSize,
      terminalCursor = s.cursor ?: defaults.terminalCursor,
      windowOpacity = s.opacity ?: defaults.windowOpacity,
    )
  }
}
