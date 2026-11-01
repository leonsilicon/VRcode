package dev.improve.vrcode

import org.junit.Assert.*
import org.junit.Test

class MenuButtonGateTest {
  @Test fun aHeldMenuButtonTogglesOnlyOnce() {
    val gate = MenuButtonGate()
    assertTrue(gate.update(true, 1000))
    assertFalse(gate.update(true, 1500))
    assertFalse(gate.update(false, 1600))
    assertTrue(gate.update(true, 2000))
  }

  @Test fun theSdkButtonAndTheMenuKeyForOnePressDoNotDoubleToggle() {
    val gate = MenuButtonGate()
    assertTrue(gate.update(true, 1000))
    assertFalse(gate.request(1021)) // Android menu key for the same press
    assertFalse(gate.update(false, 1030))
    assertTrue(gate.update(true, 1500))
  }

  @Test fun noMenuSignalDoesNotOpenSettings() {
    val gate = MenuButtonGate()
    assertFalse(gate.update(false, 1000))
    assertFalse(gate.update(false, 2000))
  }

  @Test fun menuKeyRepeatsAreDebounced() {
    val gate = MenuButtonGate()
    assertTrue(gate.request(1000))
    assertFalse(gate.request(1100))
    assertTrue(gate.request(1500))
  }
}
