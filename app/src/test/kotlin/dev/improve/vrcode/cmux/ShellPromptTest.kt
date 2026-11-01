package dev.improve.vrcode.cmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPromptTest {
  private val zsh = "leonsilicon@Leons-MacBook-Pro-5 xmux %"

  @Test fun theShellInTheForegroundRunsNothing() {
    assertTrue(ShellPrompt.shellInForeground("/bin/zsh", "/bin/zsh"))
    assertTrue(ShellPrompt.shellInForeground("/bin/zsh", "-zsh"))
    // A nested shell of the same kind (nix develop, poetry shell) waits at a prompt too.
    assertTrue(ShellPrompt.shellInForeground("/bin/zsh", "/opt/homebrew/bin/zsh"))
  }

  @Test fun aProgramInTheForegroundIsBusy() {
    assertFalse(ShellPrompt.shellInForeground("/bin/zsh", "/opt/homebrew/Caskroom/claude-code@latest/2.1.316/claude"))
    assertFalse(ShellPrompt.shellInForeground("/bin/zsh", "/Users/me/.vite-plus/js_runtime/node/26.13.0/bin/node"))
    assertFalse(ShellPrompt.shellInForeground("/bin/zsh", "/bin/sh"))
    assertFalse(ShellPrompt.shellInForeground("/bin/zsh", null))
    // A terminal started on a program rather than a shell is never typed into.
    assertFalse(ShellPrompt.shellInForeground("/usr/bin/htop", "/usr/bin/htop"))
    assertFalse(ShellPrompt.shellInForeground(null, "/bin/zsh"))
  }

  @Test fun aFreshPromptIsEmpty() {
    // As cmux reads it: trailing blank rows dropped, the cursor after the prompt's trailing space.
    assertTrue(ShellPrompt.emptyPrompt(zsh, cursorRow = 0, cursorCol = 39, cols = 120))
    val screen = "$ ls\nREADME.md  app\n$ \n\n"
    assertTrue(ShellPrompt.emptyPrompt(screen, cursorRow = 2, cursorCol = 2, cols = 80))
    assertTrue(ShellPrompt.emptyPrompt("~/code\n❯ ", cursorRow = 1, cursorCol = 2, cols = 80))
  }

  @Test fun typedTextIsNotEmpty() {
    assertFalse(ShellPrompt.emptyPrompt("$zsh git st", cursorRow = 0, cursorCol = 45, cols = 120))
    // Typed, then the cursor moved back to the start of the line.
    assertFalse(ShellPrompt.emptyPrompt("$zsh git st", cursorRow = 0, cursorCol = 39, cols = 120))
    // A completion list or a multi-line command below the cursor.
    assertFalse(ShellPrompt.emptyPrompt("$zsh git \ncommit  checkout", cursorRow = 0, cursorCol = 43, cols = 120))
  }

  @Test fun aRightPromptAtTheEdgeIsNotTypedText() {
    val row = "~/code ❯ " + " ".repeat(62) + "10:32:01"
    assertTrue(ShellPrompt.emptyPrompt(row, cursorRow = 0, cursorCol = 9, cols = row.length + 1))
    assertFalse(ShellPrompt.emptyPrompt(row, cursorRow = 0, cursorCol = 9, cols = row.length + 10))
  }

  @Test fun wideCharactersInThePromptCountTwoCells() {
    // 🚀 takes cells 0-1, so the cursor after "🚀 % " is at cell 5.
    assertTrue(ShellPrompt.emptyPrompt("🚀 % ", cursorRow = 0, cursorCol = 5, cols = 80))
    assertFalse(ShellPrompt.emptyPrompt("🚀 % ls", cursorRow = 0, cursorCol = 5, cols = 80))
  }

  @Test fun anythingElseIsBusy() {
    assertFalse(ShellPrompt.emptyPrompt("› Ask Codex to do anything\n\n  gpt-5 high", cursorRow = 0, cursorCol = 2, cols = 80))
    assertFalse(ShellPrompt.emptyPrompt("Compiling...", cursorRow = 0, cursorCol = 12, cols = 80))
    assertFalse(ShellPrompt.emptyPrompt("", cursorRow = 0, cursorCol = 0, cols = 80))
    assertFalse(ShellPrompt.emptyPrompt(zsh, cursorRow = 3, cursorCol = 0, cols = 120))
  }
}
