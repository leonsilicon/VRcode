package dev.improve.vrcode.hands

import dev.improve.vrcode.hands.HandAim.Companion.AIM_VALID
import dev.improve.vrcode.hands.HandAim.Companion.INDEX_PINCHING
import dev.improve.vrcode.hands.HandAim.Companion.MIDDLE_PINCHING
import dev.improve.vrcode.hands.HandAim.Companion.SYSTEM_GESTURE
import dev.improve.vrcode.hands.HandMenuGesture.Event
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandMenuGestureTest {
  private val h = (sqrt(2.0) / 2).toFloat()

  /** Palm joint orientations (x, y, z, w) in VIEW space for a hand 40 cm ahead, fingers up. */
  // -90° about X: the palm's +Y (back of the hand) points away (-Z), so -Y points at the head.
  private val palmToUser = floatArrayOf(-h, 0f, 0f, h)
  // +90° about X: the back of the hand faces the head.
  private val palmAway = floatArrayOf(h, 0f, 0f, h)

  /**
   * A synthetic open hand in VIEW space, 40 cm in front of the head with the fingers up. The joint
   * positions are the same for either hand and either facing: palm direction comes only from the
   * palm joint's orientation, as on the device. [middleGapM] and [indexGapM] are the thumb tip's
   * distance to the middle and index fingertips.
   */
  private fun joints(middleGapM: Float = 0.06f, indexGapM: Float = 0.06f): FloatArray {
    val p = FloatArray(FistShape.JOINT_COUNT * 3)
    fun set(i: Int, x: Float, y: Float, z: Float = -0.4f) {
      p[i * 3] = x
      p[i * 3 + 1] = y
      p[i * 3 + 2] = z
    }
    set(PalmShape.WRIST, 0f, -0.1f)
    set(PalmShape.PALM, 0f, -0.05f)
    for ((f, finger) in FistShape.FINGERS.withIndex()) {
      for (k in 0..4) set(finger[k], 0.03f - f * 0.02f, -0.095f + k * 0.03f)
    }
    val middleTip = PalmShape.MIDDLE_TIP
    val thumbX = p[middleTip * 3]
    val thumbY = p[middleTip * 3 + 1] - middleGapM
    set(PalmShape.THUMB_TIP, thumbX, thumbY)
    set(PalmShape.INDEX_TIP, thumbX + indexGapM, thumbY)
    for (i in 2..4) set(i, 0.04f, -0.08f + i * 0.01f)
    return p
  }

  /** A rolled fist (each finger bent 90°, 100°, 60°) with the thumb over the index finger. */
  private fun fist(): FloatArray {
    val p = FloatArray(FistShape.JOINT_COUNT * 3)
    fun set(i: Int, v: FloatArray) = v.copyInto(p, i * 3)
    set(PalmShape.PALM, floatArrayOf(0f, -0.05f, -0.4f))
    set(PalmShape.WRIST, floatArrayOf(0f, -0.1f, -0.4f))
    val lengths = floatArrayOf(0.065f, 0.04f, 0.025f, 0.02f)
    val bends = floatArrayOf(0f, 90f, 100f, 60f)
    for ((f, finger) in FistShape.FINGERS.withIndex()) {
      var pos = floatArrayOf(0.02f - f * 0.02f, -0.095f, -0.4f)
      var angle = 0.0
      set(finger[0], pos)
      for (k in 0 until 4) {
        angle += Math.toRadians(bends[k].toDouble())
        pos = floatArrayOf(pos[0], pos[1] + lengths[k] * cos(angle).toFloat(), pos[2] + lengths[k] * sin(angle).toFloat())
        set(finger[k + 1], pos)
      }
    }
    for (i in 2..4) set(i, floatArrayOf(0.04f, -0.07f + i * 0.01f, -0.4f))
    set(PalmShape.THUMB_TIP, floatArrayOf(p[8 * 3], p[8 * 3 + 1], p[8 * 3 + 2] + 0.015f))
    return p
  }

  private fun frame(
    joints: FloatArray = joints(),
    palm: FloatArray = palmToUser,
    flags: Int = AIM_VALID,
    middle: Float = 0f,
    index: Float = 0f,
  ) = HandAim.Frame(joints, FloatArray(FistShape.JOINT_COUNT) { 0.008f }, palm, flags, index, middle)

  private val pinch = AIM_VALID or MIDDLE_PINCHING

  /** One frame at ~72 Hz. */
  private var clockMs = 0L
  private fun HandMenuGesture.step(frame: HandAim.Frame?, afterMs: Long = 14L) = update(frame, clockMs.also { clockMs += afterMs })

  @Test
  fun thePalmNormalComesFromThePalmJointForEitherHand() {
    val toUser = PalmShape.normal(palmToUser)
    assertEquals(1f, toUser[2], 1e-4f) // toward the head at the origin (+Z from z = -0.4)
    // The palm sits 5 cm below eye level, 40 cm ahead: atan(0.05 / 0.4) ≈ 7° off the normal.
    assertEquals(7.1f, PalmShape.facingDegrees(joints(), palmToUser), 0.2f)
    assertEquals(172.9f, PalmShape.facingDegrees(joints(), palmAway), 0.2f)
    // An identity palm (back of the hand up) faces the floor: 90° plus that 7° from the head.
    assertEquals(97.1f, PalmShape.facingDegrees(joints(), floatArrayOf(0f, 0f, 0f, 1f)), 0.2f)
  }

  @Test
  fun theRuntimeMiddlePinchOpensWithThePalmTowardTheUserAndReleaseEnds() {
    val g = HandMenuGesture()
    assertEquals(Event.None, g.step(frame(middle = 0.3f)))
    assertEquals(Event.Opened, g.step(frame(flags = pinch, middle = 1f)))
    assertTrue(g.open)
    assertEquals(MiddlePinch.Source.Runtime, g.source)
    // A one-frame dropout of the discrete signal with the strength still high keeps it held.
    assertEquals(Event.None, g.step(frame(middle = 0.8f)))
    assertEquals(Event.Released, g.step(frame(middle = 0.2f)))
    assertFalse(g.open)
  }

  @Test
  fun closeFingertipsDoNotPinchWhileTheRuntimeReportsNoPinch() {
    val g = HandMenuGesture()
    g.step(frame())
    // The joints say the fingertips touch, but the runtime's classifier says no: trust the runtime.
    assertEquals(Event.None, g.step(frame(joints = joints(middleGapM = 0.012f), middle = 0.4f)))
    assertFalse(g.open)
  }

  @Test
  fun aPalmAwayMiddlePinchIsTheRightClickNotTheMenu() {
    val g = HandMenuGesture()
    g.step(frame(palm = palmAway))
    assertEquals(Event.None, g.step(frame(palm = palmAway, flags = pinch, middle = 1f)))
    assertFalse(g.palmFacing)
    assertFalse(g.hint)
  }

  @Test
  fun theRuntimesPalmFacingFlagAloneLetsThePinchOpen() {
    val g = HandMenuGesture()
    g.step(frame(palm = palmAway, flags = AIM_VALID or SYSTEM_GESTURE, middle = 0.2f))
    assertTrue(g.hint)
    assertTrue(g.palmFacing)
    assertEquals(Event.Opened, g.step(frame(palm = palmAway, flags = pinch or SYSTEM_GESTURE, middle = 1f)))
  }

  @Test
  fun whenTheRuntimeWithholdsPinchDataTheJointsTouchingStandIn() {
    val g = HandMenuGesture()
    val withheld = AIM_VALID or SYSTEM_GESTURE // palm-facing system pose, both strengths zero
    g.step(frame(joints = joints(middleGapM = 0.06f), flags = withheld))
    assertEquals(MiddlePinch.Source.Joints, g.source)
    // Tip radii 8 mm each: surfaces touch at 16 mm, plus 6 mm slack to start.
    assertEquals(Event.Opened, g.step(frame(joints = joints(middleGapM = 0.02f), flags = withheld)))
    assertEquals(Event.None, g.step(frame(joints = joints(middleGapM = 0.03f), flags = withheld))) // release slack holds
    assertEquals(Event.Released, g.step(frame(joints = joints(middleGapM = 0.045f), flags = withheld)))
  }

  @Test
  fun aDominantIndexPinchCancelsAndNeedsAFreshPinch() {
    val g = HandMenuGesture()
    g.step(frame())
    g.step(frame(flags = pinch, middle = 1f))
    assertEquals(Event.Cancelled, g.step(frame(flags = pinch or INDEX_PINCHING, middle = 0.6f, index = 1f)))
    assertEquals(Event.None, g.step(frame(flags = pinch, middle = 1f)))
    g.step(frame(middle = 0.1f))
    assertEquals(Event.Opened, g.step(frame(flags = pinch, middle = 1f)))
  }

  @Test
  fun anIndexFingerMerelyNearbyDoesNotCancel() {
    val g = HandMenuGesture()
    g.step(frame())
    g.step(frame(flags = pinch, middle = 1f))
    assertEquals(Event.None, g.step(frame(flags = pinch or INDEX_PINCHING, middle = 1f, index = 0.7f)))
    assertTrue(g.open)
  }

  @Test
  fun aFistIsVoiceNotAMenuEvenIfTheRuntimeReportsAMiddlePinch() {
    val g = HandMenuGesture()
    g.step(frame(joints = fist(), middle = 0.2f))
    assertEquals(Event.None, g.step(frame(joints = fist(), flags = pinch, middle = 1f)))
    assertFalse(g.open)
    assertFalse(g.hint)
  }

  @Test
  fun aSingleDroppedFrameDoesNotCloseTheMenu() {
    val g = HandMenuGesture()
    g.step(frame())
    g.step(frame(flags = pinch, middle = 1f))
    repeat(3) { assertEquals(Event.None, g.step(null)) } // ~40 ms: the runtime drops frames like this
    assertTrue(g.open)
    assertEquals(Event.None, g.step(frame(flags = pinch, middle = 1f)))
    assertEquals(Event.Released, g.step(frame(middle = 0f)))
  }

  @Test
  fun losingTrackingCancelsAndAStillPinchedHandMustReleaseFirst() {
    val g = HandMenuGesture()
    g.step(frame())
    g.step(frame(flags = pinch, middle = 1f))
    g.step(null, afterMs = HandMenuGesture.TRACKING_GAP_MS)
    assertEquals(Event.Cancelled, g.step(null))
    assertFalse(g.open)
    assertEquals(Event.None, g.step(frame(flags = pinch, middle = 1f)))
    g.step(frame(middle = 0f))
    assertEquals(Event.Opened, g.step(frame(flags = pinch, middle = 1f)))
  }

  @Test
  fun aPinchAlreadyHeldWhenThePalmTurnsTowardTheUserDoesNotOpen() {
    val g = HandMenuGesture()
    g.step(frame(palm = palmAway, flags = pinch, middle = 1f))
    assertEquals(Event.None, g.step(frame(flags = pinch, middle = 1f)))
  }

  @Test
  fun theHintFillsWithTheRuntimesPinchStrength() {
    val g = HandMenuGesture()
    g.step(frame(middle = 0f))
    assertTrue(g.hint)
    assertEquals(0f, g.progress, 0.001f)
    g.step(frame(middle = 0.55f))
    assertEquals(0.55f, g.progress, 0.001f)
    // The hint sits beyond the middle fingertip (fingers point up, +y) and toward the user (+z).
    val tip = joints()
    assertTrue(g.hintPoint!![1] > tip[PalmShape.MIDDLE_TIP * 3 + 1])
    assertTrue(g.hintPoint!![2] > tip[PalmShape.MIDDLE_TIP * 3 + 2])
  }

  @Test
  fun cancellingAnOpenMenuNeedsAFreshPinch() {
    val g = HandMenuGesture()
    g.step(frame())
    g.step(frame(flags = pinch, middle = 1f))
    g.cancel() // e.g. the hand moved onto an item, which ran
    assertFalse(g.open)
    assertEquals(Event.None, g.step(frame(flags = pinch, middle = 1f)))
    assertEquals(Event.None, g.step(frame(middle = 0f)))
    assertEquals(Event.Opened, g.step(frame(flags = pinch, middle = 1f)))
  }
}
