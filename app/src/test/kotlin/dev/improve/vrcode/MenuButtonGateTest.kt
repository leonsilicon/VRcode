package dev.improve.vrcode

import org.junit.Assert.*
import org.junit.Test

class MenuButtonGateTest {
  @Test fun aHeldPalmPinchTogglesOnlyOnce() {
    val gate = MenuButtonGate()
    assertTrue(gate.update(false, true, 1000))
    assertFalse(gate.update(false, true, 1500))
    assertFalse(gate.update(false, false, 1600))
    assertTrue(gate.update(false, true, 2000))
  }

  @Test fun sdkAndNativeReportsOfTheSameGestureDoNotDoubleToggle() {
    val gate = MenuButtonGate()
    assertTrue(gate.update(true, false, 1000))
    assertFalse(gate.update(true, true, 1010))
    assertFalse(gate.update(false, true, 1020))
    assertFalse(gate.request(1021)) // Android menu key for the same press
    assertFalse(gate.update(false, false, 1030))
    assertTrue(gate.update(true, false, 1500))
  }

  @Test fun noMenuSignalDoesNotOpenSettings() {
    val gate = MenuButtonGate()
    assertFalse(gate.update(false, false, 1000))
    assertFalse(gate.update(false, false, 2000))
  }

  @Test fun menuKeyRepeatsAreDebounced() {
    val gate = MenuButtonGate()
    assertTrue(gate.request(1000))
    assertFalse(gate.request(1100))
    assertTrue(gate.request(1500))
  }
}
