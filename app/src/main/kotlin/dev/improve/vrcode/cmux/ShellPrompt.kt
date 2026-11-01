package dev.improve.vrcode.cmux

/**
 * Whether a terminal's shell waits at an empty prompt, so a launch command can be typed into it
 * without reaching a running program or joining text the user already typed. The shells cmux
 * starts emit no prompt marks (OSC 133), so the prompt is read off the screen. Anything unclear
 * reads as busy: the caller then opens a new terminal instead.
 */
internal object ShellPrompt {
  private val SHELLS = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "mksh", "tcsh", "csh", "nu", "xonsh", "pwsh", "elvish")
  /** What prompts end with: zsh `%`, bash `$`, root `#`, fish/pwsh `>`, starship/p10k `❯`, oh-my-zsh `➜`, … */
  private const val PROMPT_ENDS = "%$#>❯➜λ»›→▶"

  /**
   * True when the PTY's foreground process group is led by a shell of the terminal's own kind
   * ([shell] is its executable, [foreground] the group leader's): no command is running. A nested
   * shell of the same kind counts; another program, or an unknown leader, does not.
   */
  fun shellInForeground(shell: String?, foreground: String?): Boolean {
    val name = shell?.let(::programName)?.takeIf { it in SHELLS } ?: return false
    return foreground?.let(::programName) == name
  }

  /**
   * True when the cursor sits just after a prompt with nothing typed: its row up to the cursor ends
   * with a prompt character, and nothing follows on that row (except a right prompt reaching the
   * right edge, as zsh's RPROMPT does) or below it. [text] is the visible screen, one line per row.
   */
  fun emptyPrompt(text: String, cursorRow: Int, cursorCol: Int, cols: Int): Boolean {
    val rows = text.split('\n')
    if (rows.drop(cursorRow + 1).any { it.isNotBlank() }) return false
    val row = rows.getOrNull(cursorRow)?.trimEnd() ?: return false
    val split = indexAtCell(row, cursorCol)
    val before = row.substring(0, split).trimEnd()
    val after = row.substring(split)
    if (before.isEmpty() || before.last() !in PROMPT_ENDS) return false
    if (after.isBlank()) return true
    // Typed text sits next to the cursor; a right prompt is set apart and ends at the right edge.
    return after.startsWith("  ") && cellWidth(row) >= cols - RPROMPT_INDENT_MAX
  }

  /** The string index of the character at [cell], counting wide characters as two cells. */
  private fun indexAtCell(row: String, cell: Int): Int {
    var index = 0
    var cells = 0
    while (index < row.length && cells < cell) {
      val codePoint = row.codePointAt(index)
      cells += width(codePoint)
      index += Character.charCount(codePoint)
    }
    return index
  }

  private fun cellWidth(row: String): Int = row.codePoints().map(::width).sum()

  /** East Asian wide characters and emoji take two cells; everything else one. */
  private fun width(codePoint: Int): Int = when (codePoint) {
    in 0x1100..0x115F, in 0x2E80..0x303E, in 0x3041..0xA4CF, in 0xAC00..0xD7A3, in 0xF900..0xFAFF,
    in 0xFE30..0xFE4F, in 0xFF00..0xFF60, in 0xFFE0..0xFFE6, in 0x1F300..0x1F64F, in 0x1F680..0x1F6FF,
    in 0x1F900..0x1FAFF, in 0x20000..0x3FFFD -> 2
    else -> 1
  }

  private fun programName(path: String): String = path.substringAfterLast('/').removePrefix("-")

  /** zsh leaves ZLE_RPROMPT_INDENT (1 by default) free right of RPROMPT; allow one more. */
  private const val RPROMPT_INDENT_MAX = 2
}
