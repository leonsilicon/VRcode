package dev.improve.vrcode.hands

import dev.improve.vrcode.hands.FistGate.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FistGateTest {
  /** Feeds [frames] at 10 ms steps from [startMs] and returns every change with its time. */
  private fun FistGate.run(startMs: Long, frames: Int, tracked: Boolean, fist: Boolean): List<Pair<Long, Change>> =
    (0 until frames).mapNotNull { i ->
      val t = startMs + i * 10L
      update(tracked, fist, t).takeIf { it != Change.None }?.let { t to it }
    }

  @Test
  fun startsAfterAShortHoldAndEndsWhenTheHandIsSeenOpen() {
    val gate = FistGate(onMs = 120, offMs = 400)
    assertEquals(listOf(120L to Change.Started), gate.run(0, 50, tracked = true, fist = true))
    assertEquals(listOf(900L to Change.Ended), gate.run(500, 60, tracked = true, fist = false))
  }

  @Test
  fun aLongHeldFistNeverEndsByItself() {
    // Holding the fist and staying silent for a minute: nothing ends voice mode.
    val gate = FistGate()
    gate.run(0, 20, tracked = true, fist = true)
    assertTrue(gate.run(200, 6000, tracked = true, fist = true).isEmpty())
    assertTrue(gate.held)
  }

  @Test
  fun losingTrackingHoldsVoiceMode() {
    val gate = FistGate()
    gate.run(0, 20, tracked = true, fist = true)
    // The fist leaves the cameras' view for ten seconds; the runtime reports no fist meanwhile.
    assertTrue(gate.run(200, 1000, tracked = false, fist = false).isEmpty())
    assertTrue(gate.held)
    // Back in view, still a fist.
    assertTrue(gate.run(10_200, 100, tracked = true, fist = true).isEmpty())
    assertTrue(gate.held)
  }

  @Test
  fun aBriefFlickerDoesNotEndIt() {
    val gate = FistGate(offMs = 400)
    gate.run(0, 20, tracked = true, fist = true)
    assertTrue(gate.run(200, 30, tracked = true, fist = false).isEmpty()) // 300 ms open
    assertTrue(gate.run(500, 10, tracked = true, fist = true).isEmpty())
    assertTrue(gate.held)
  }

  @Test
  fun anOpenHandInterruptedByTrackingLossRestartsTheReleaseTimer() {
    val gate = FistGate(offMs = 400)
    gate.run(0, 20, tracked = true, fist = true)
    gate.run(200, 30, tracked = true, fist = false) // 300 ms open
    gate.run(500, 50, tracked = false, fist = false)
    assertEquals(listOf(1400L to Change.Ended), gate.run(1000, 60, tracked = true, fist = false))
  }

  @Test
  fun aQuickReleaseEndsSoonerThanTheUsualDebounce() {
    // Nothing said yet: the caller asks for a 100 ms release instead of 400 ms.
    val gate = FistGate(onMs = 120, offMs = 400)
    gate.run(0, 20, tracked = true, fist = true)
    val ended = (0 until 50).firstNotNullOfOrNull { i ->
      val t = 500L + i * 10L
      t.takeIf { gate.update(tracked = true, fist = false, nowMs = t, releaseMs = 100) == Change.Ended }
    }
    assertEquals(600L, ended)
  }
}
