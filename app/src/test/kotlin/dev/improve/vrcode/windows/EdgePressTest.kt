package dev.improve.vrcode.windows

import com.meta.spatial.runtime.ButtonBits
import dev.improve.vrcode.hands.FistShape
import dev.improve.vrcode.hands.HandAim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgePressTest {
  private fun frame(flags: Int = HandAim.AIM_VALID, strength: Float = 0f) = HandAim.Frame(
    FloatArray(FistShape.JOINT_COUNT * 3), FloatArray(FistShape.JOINT_COUNT),
    floatArrayOf(0f, 0f, 0f, 1f), flags, strength, 0f,
  )

  @Test fun eitherHandCanGrabAndReleaseAnEdgeWithoutAnyPanelTriggerBits() {
    for (left in listOf(true, false)) {
      val press = EdgePress(left)
      val grip = EdgeGrip<String>()
      fun tick(time: Long, f: HandAim.Frame): String? =
        grip.press(time, press.update(isHand = true, buttons = 0, frame = f) == true, "edge") { true }
      assertNull(tick(0, frame())) // tracked open hand arms the grip
      assertEquals("edge", tick(10, frame(HandAim.AIM_VALID or HandAim.INDEX_PINCHING, 1f)))
      assertTrue(grip.hold(20, press.update(true, 0, frame(strength = 0.7f)) == true))
      assertTrue(grip.hold(30, press.update(true, 0, frame()) == true))
      assertFalse(grip.hold(30 + EdgeGrip.RELEASE_GRACE_MS, press.update(true, 0, frame()) == true))
    }
  }

  @Test fun handsNeverUseStaleOrSyntheticControllerBitsAsAPinch() {
    val press = EdgePress(left = false)
    val bits = ButtonBits.ButtonTriggerR or ButtonBits.ButtonA
    assertEquals(false, press.update(true, bits, frame()))
    assertEquals(false, press.update(true, bits, frame(HandAim.AIM_VALID or HandAim.MIDDLE_PINCHING)))
    assertNull(press.update(true, bits, null))
    assertNull(press.update(true, bits, frame(HandAim.AIM_VALID or HandAim.INDEX_PINCHING or HandAim.SYSTEM_GESTURE, 1f)))
  }

  @Test fun controllersKeepTheirOwnTriggerAndPanelSelectButtons() {
    for ((left, buttons, otherSide) in listOf(
      Triple(true, listOf(ButtonBits.ButtonTriggerL, ButtonBits.ButtonX), ButtonBits.ButtonTriggerR or ButtonBits.ButtonA),
      Triple(false, listOf(ButtonBits.ButtonTriggerR, ButtonBits.ButtonA), ButtonBits.ButtonTriggerL or ButtonBits.ButtonX),
    )) {
      val press = EdgePress(left)
      for (button in buttons) assertEquals(true, press.update(false, button, null))
      assertEquals(false, press.update(false, 0, null))
      assertEquals(false, press.update(false, otherSide, null))
    }
  }

  @Test fun missingTrackingDoesNotArmANewGrabWhenTheHandReturnsAlreadyPinching() {
    val press = EdgePress(left = false)
    val grip = EdgeGrip<String>()
    assertNull(press.update(true, 0, null))
    grip.lost()
    val closed = frame(HandAim.AIM_VALID or HandAim.INDEX_PINCHING, 1f)
    assertNull(grip.press(10, press.update(true, 0, closed) == true, "edge") { true })
    assertNull(grip.press(20, press.update(true, 0, frame()) == true, "edge") { true })
    assertEquals("edge", grip.press(30, press.update(true, 0, closed) == true, "edge") { true })
  }

  @Test fun losingAnActiveDragRequiresAnOpenHandBeforeAnotherGrab() {
    val grip = EdgeGrip<String>()
    grip.press(0, false, "edge") { true }
    assertEquals("edge", grip.press(10, true, "edge") { true })
    grip.lost()
    assertTrue(grip.hold(20, null))
    grip.lost()
    assertFalse(grip.hold(20 + EdgeGrip.RELEASE_GRACE_MS, null))
    assertNull(grip.press(200, true, "edge") { true })
    assertNull(grip.press(210, false, "edge") { true })
    assertEquals("edge", grip.press(220, true, "edge") { true })
  }
}
