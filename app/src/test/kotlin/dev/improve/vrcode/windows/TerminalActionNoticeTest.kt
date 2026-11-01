package dev.improve.vrcode.windows

import org.junit.Assert.*
import org.junit.Test

class TerminalActionNoticeTest {
  private val notices = TerminalActionNotices<String>()

  @Test fun anotherTerminalsActionCannotRedirectALateFailure() {
    val claude = notices.start("terminal A", "Claude Code", "Launching…")
    val copy = notices.start("terminal B", "Copy screen", "Copying…")
    notices.finish(copy, "Copied", failed = false, now = 100)
    notices.finish(claude, "Could not run Claude Code", failed = true, now = 200)
    assertEquals("terminal A", claude.target)
    assertEquals(TerminalActionNotice.State.Failed, claude.state)
    assertEquals("terminal B", copy.target)
    assertEquals("Copied", copy.message)
  }

  @Test fun simultaneousActionsOnOneTerminalKeepTheirOwnResults() {
    val first = notices.start("A", "Claude Code", "Checking…")
    val second = notices.start("A", "Copy", "Copying…")
    notices.finish(first, "Failed", failed = true, now = 100)
    assertEquals(listOf(first, second), notices.entries.toList())
    assertEquals(TerminalActionNotice.State.Running, second.state)
  }

  @Test fun creationProgressMovesToTheReturnedTerminal() {
    val launch = notices.start("source", "Claude Code", "Opening…")
    launch.target = "created"
    notices.finish(launch, "Launch failed", failed = true, now = 100)
    notices.prune(listOf("created"), now = 200)
    assertEquals(listOf(launch), notices.entries.toList())
    assertEquals("created", launch.target)
  }

  @Test fun firstTerminalCanBeTargetedAfterItMounts() {
    val launch = notices.start(null, "New terminal", "Opening…")
    notices.prune(emptyList(), now = 200)
    launch.target = "created"
    assertEquals(listOf(launch), notices.entries.toList())
  }

  @Test fun successDisappearsImmediatelyWithoutHidingRunningActionsOrFailures() {
    val running = notices.start("A", "Wait", "Waiting…")
    val done = notices.start("B", "Copy", "Copying…")
    val failed = notices.start("C", "Claude Code", "Launching…")
    notices.finish(done, "Copied", failed = false, now = 100)
    notices.finish(failed, "Failed", failed = true, now = 100)
    assertEquals(TerminalActionNotice.State.Done, done.state)
    assertEquals(listOf(running, failed), notices.entries.toList())
    notices.prune(listOf("A", "B", "C"), now = 10_099)
    assertEquals(listOf(running, failed), notices.entries.toList())
    notices.prune(listOf("A", "B", "C"), now = 10_100)
    assertEquals(listOf(running), notices.entries.toList())
  }

  @Test fun closingATerminalDropsItsInfoInsteadOfMovingItToAnotherWindow() {
    notices.start("closed", "Claude Code", "Launching…")
    val remaining = notices.start("open", "Copy", "Copying…")
    notices.prune(listOf("open"), now = 100)
    assertEquals(listOf(remaining), notices.entries.toList())
  }

  @Test fun newActionsReplaceOnlyFinishedResultsOnTheirOwnTerminal() {
    val old = notices.start("A", "Old result", "Running…")
    notices.finish(old, "Failed", failed = true, now = 100)
    val running = notices.start("B", "Other terminal", "Waiting…")
    val newest = notices.start("A", "New action", "Running…")
    assertEquals(listOf(running, newest), notices.entries.toList())
  }
}
