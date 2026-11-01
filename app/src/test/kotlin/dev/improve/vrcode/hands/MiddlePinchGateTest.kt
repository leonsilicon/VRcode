package dev.improve.vrcode.hands

import dev.improve.vrcode.hands.MiddlePinchGate.Signal.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiddlePinchGateTest {
  @Test fun holdingAPinchClicksOnceAndReleasingAllowsAnotherClick() {
    val gate = MiddlePinchGate()
    assertFalse(gate.update(Released))
    assertTrue(gate.update(Pinched))
    repeat(90) { assertFalse(gate.update(Pinched)) }
    assertFalse(gate.update(Released))
    assertTrue(gate.update(Pinched))
  }

  @Test fun anAlreadyHeldPinchAtStartupNeedsARelease() {
    val gate = MiddlePinchGate()
    repeat(10) { assertFalse(gate.update(Pinched)) }
    assertFalse(gate.update(Released))
    assertTrue(gate.update(Pinched))
  }

  @Test fun reacquiringAStillPinchedHandDoesNotClickAgain() {
    val gate = MiddlePinchGate()
    gate.update(Released)
    assertTrue(gate.update(Pinched))
    assertFalse(gate.update(null))
    assertFalse(gate.update(Pinched))
    gate.update(Released)
    assertTrue(gate.update(Pinched))
  }

  @Test fun losingTrackingBeforeAPinchAlsoRequiresARelease() {
    val gate = MiddlePinchGate()
    gate.update(Released)
    gate.update(null)
    assertFalse(gate.update(Pinched))
    gate.update(Released)
    assertTrue(gate.update(Pinched))
  }

  @Test fun liftingTheIndexOrEndingASystemGestureWhileMiddleIsHeldDoesNotClick() {
    val gate = MiddlePinchGate()
    gate.update(Released)
    assertFalse(gate.update(Blocked))
    repeat(10) { assertFalse(gate.update(Pinched)) }
    gate.update(Released)
    assertTrue(gate.update(Pinched))
  }

  @Test fun leavingSettingsOrResumingTheAppNeedsAFreshPinch() {
    val gate = MiddlePinchGate()
    gate.update(Released)
    gate.cancel()
    assertFalse(gate.update(Pinched))
    gate.update(Released)
    assertTrue(gate.update(Pinched))
  }
}
