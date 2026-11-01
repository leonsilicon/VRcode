package dev.improve.vrcode.dock

import org.junit.Assert.*
import org.junit.Test

class DockRevealTest {
  @Test fun collapsedUntilReachedThenHeldBrieflyAfterContactEnds() {
    val reveal = DockReveal(holdMs = 1_000)
    assertFalse(reveal.update(reached = false, nowMs = 0))
    assertTrue(reveal.update(reached = true, nowMs = 100))
    assertTrue(reveal.update(reached = false, nowMs = 1_100))
    assertFalse(reveal.update(reached = false, nowMs = 1_101))
    assertFalse(reveal.expanded)
  }

  @Test fun continuousContactKeepsItOpen() {
    val reveal = DockReveal(holdMs = 500)
    for (t in 0L..5_000L step 100) assertTrue(reveal.update(reached = true, nowMs = t))
  }

  @Test fun pressingTheHandleOpensItAndCollapseResets() {
    val reveal = DockReveal(holdMs = 500)
    reveal.reveal(nowMs = 10)
    assertTrue(reveal.update(reached = false, nowMs = 400))
    reveal.collapse()
    assertFalse(reveal.update(reached = false, nowMs = 410))
  }

  @Test fun aClockThatGoesBackwardsDoesNotPinItOpen() {
    val reveal = DockReveal(holdMs = 500)
    reveal.update(reached = true, nowMs = 10_000)
    assertFalse(reveal.update(reached = false, nowMs = 5_000))
  }
}
