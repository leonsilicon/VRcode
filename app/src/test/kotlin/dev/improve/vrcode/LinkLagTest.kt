package dev.improve.vrcode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkLagTest {
  @Test fun fastRepliesOnScheduleAreNotLag() {
    val lag = LinkLag()
    lag.start(0)
    for (t in 2_000L..20_000L step 2_000L) {
      lag.onReply(40, t + 40)
      assertFalse(lag.lagging(t + 40))
      assertFalse(lag.lagging(t + 1_900))
    }
  }

  @Test fun aSlowRoundTripIsLag() {
    val lag = LinkLag()
    lag.start(0)
    lag.onReply(450, 2_450)
    assertTrue(lag.lagging(2_450))
  }

  @Test fun anOverdueReplyIsLagBeforeItArrives() {
    val lag = LinkLag()
    lag.start(0)
    lag.onReply(30, 2_030)
    assertFalse(lag.lagging(4_200))
    // The next reply was due near 4 030; by 4 400 it is overdue.
    assertTrue(lag.lagging(4_400))
  }

  @Test fun lagIsHeldBrieflySoTheSpinnerDoesNotFlicker() {
    val lag = LinkLag()
    lag.start(0)
    lag.onReply(400, 2_400)
    assertTrue(lag.lagging(2_400))
    lag.onReply(30, 4_430)
    assertTrue("still held right after a fast reply", lag.lagging(3_800))
    assertFalse("clear once the hold passes", lag.lagging(4_430))
  }

  @Test fun nothingIsLagBeforeTheLinkStarts() {
    assertFalse(LinkLag().lagging(10_000))
  }
}
