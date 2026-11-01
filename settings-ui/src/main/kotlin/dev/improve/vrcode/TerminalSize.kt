package dev.improve.vrcode

/** Initial terminal grid size; changing the preference never resizes an existing terminal. */
data class TerminalSize(val cols: Int = DEFAULT_COLS, val rows: Int = DEFAULT_ROWS) {
  fun sanitized() = copy(cols = cols.coerceIn(MIN_COLS, MAX_COLS), rows = rows.coerceIn(MIN_ROWS, MAX_ROWS))

  companion object {
    const val DEFAULT_COLS = 120
    const val DEFAULT_ROWS = 36
    const val MIN_COLS = 40
    const val MAX_COLS = 200
    const val MIN_ROWS = 12
    const val MAX_ROWS = 72
  }
}

enum class TerminalSizePreset(val label: String, val size: TerminalSize) {
  Compact("Compact", TerminalSize(80, 24)),
  Standard("Standard", TerminalSize()),
  Large("Large", TerminalSize(160, 48)),
  Tall("Tall", TerminalSize(100, 48)),
}
