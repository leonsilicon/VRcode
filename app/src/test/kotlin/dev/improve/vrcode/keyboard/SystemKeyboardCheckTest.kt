package dev.improve.vrcode.keyboard

import dev.improve.vrcode.keyboard.SystemKeyboardCheck.State
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemKeyboardCheckTest {
  @Test fun aKeyboardThatNeverShowsFailsAtTheTimeout() {
    val check = SystemKeyboardCheck(askedAtMs = 5_000, timeoutMs = 1_000)
    assertEquals(State.Waiting, check.update(imeVisible = false, nowMs = 5_000))
    assertEquals(State.Waiting, check.update(imeVisible = false, nowMs = 5_999))
    assertEquals(State.Failed, check.update(imeVisible = false, nowMs = 6_000))
  }

  @Test fun aKeyboardThatShowsIsReportedOnceThenKeptUntilItCloses() {
    val check = SystemKeyboardCheck(askedAtMs = 0, timeoutMs = 1_000)
    assertEquals(State.Waiting, check.update(imeVisible = false, nowMs = 100))
    assertEquals(State.Appeared, check.update(imeVisible = true, nowMs = 300))
    assertEquals(State.Showing, check.update(imeVisible = true, nowMs = 5_000))
    assertEquals(State.Closed, check.update(imeVisible = false, nowMs = 9_000))
  }

  @Test fun aKeyboardThatShowedIsClosedNotFailedOnceItGoesAway() {
    val check = SystemKeyboardCheck(askedAtMs = 0, timeoutMs = 1_000)
    check.update(imeVisible = true, nowMs = 200)
    assertEquals(State.Closed, check.update(imeVisible = false, nowMs = 400))
  }
}
