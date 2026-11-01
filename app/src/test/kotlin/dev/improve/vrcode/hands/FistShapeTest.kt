package dev.improve.vrcode.hands

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FistShapeTest {
  /**
   * A synthetic left hand: fingers start at the knuckle line and point along +Y, bending toward the
   * palm (-Z) by [mcp], [pip] and [dip] degrees at their three joints. [thumbTip] places the thumb
   * tip given the finished joint array.
   */
  private fun hand(mcp: Float, pip: Float, dip: Float, thumbTip: (FloatArray) -> FloatArray): FloatArray {
    val p = FloatArray(FistShape.JOINT_COUNT * 3)
    fun set(i: Int, v: FloatArray) = v.copyInto(p, i * 3)
    set(0, floatArrayOf(0f, 0.04f, 0f)) // palm
    set(1, floatArrayOf(0f, 0f, 0f)) // wrist
    val lengths = floatArrayOf(0.065f, 0.04f, 0.025f, 0.02f)
    val bends = floatArrayOf(0f, mcp, pip, dip)
    for ((f, finger) in FistShape.FINGERS.withIndex()) {
      var pos = floatArrayOf(0.02f - f * 0.02f, 0.005f, 0f)
      var angle = 0.0
      set(finger[0], pos)
      for (k in 0 until 4) {
        angle += Math.toRadians(bends[k].toDouble())
        pos = floatArrayOf(pos[0], pos[1] + lengths[k] * cos(angle).toFloat(), pos[2] - lengths[k] * sin(angle).toFloat())
        set(finger[k + 1], pos)
      }
    }
    for (i in 2..4) set(i, floatArrayOf(0.04f, 0.03f + i * 0.01f, 0f))
    set(FistShape.THUMB_TIP, thumbTip(p))
    return p
  }

  /** The thumb tip lying on the index finger's middle joint, slightly outside it. */
  private val wrapped: (FloatArray) -> FloatArray = { p -> floatArrayOf(p[8 * 3], p[8 * 3 + 1], p[8 * 3 + 2] - 0.015f) }

  /** The thumb pointing up and away, as in a thumbs-up. */
  private val thumbUp: (FloatArray) -> FloatArray = { floatArrayOf(0.05f, 0.16f, 0.02f) }

  @Test
  fun aRolledFistWithTheThumbWrappedIsAFist() {
    val fist = hand(mcp = 90f, pip = 100f, dip = 60f, thumbTip = wrapped)
    val m = FistShape.measure(fist)
    assertEquals(250f, m.mean, 1f)
    assertTrue(m.toString(), FistShape.isFist(fist, wasFist = false))
  }

  @Test
  fun aCuppedHandIsNotAFist() {
    // Fingers bent like holding a ball, thumb near the fingers: the runtime's own flag fires here.
    val cupped = hand(mcp = 35f, pip = 45f, dip = 25f, thumbTip = wrapped)
    assertFalse(FistShape.measure(cupped).toString(), FistShape.isFist(cupped, wasFist = false))
  }

  @Test
  fun aLooseGripIsNotEnoughToStart() {
    val grip = hand(mcp = 55f, pip = 70f, dip = 40f, thumbTip = wrapped)
    assertFalse(FistShape.isFist(grip, wasFist = false))
  }

  @Test
  fun anOpenHandIsNotAFist() {
    assertFalse(FistShape.isFist(hand(mcp = 5f, pip = 5f, dip = 5f, thumbTip = thumbUp), wasFist = false))
  }

  @Test
  fun aThumbsUpIsNotAFist() {
    val thumbsUp = hand(mcp = 90f, pip = 100f, dip = 60f, thumbTip = thumbUp)
    assertFalse(FistShape.measure(thumbsUp).toString(), FistShape.isFist(thumbsUp, wasFist = false))
  }

  @Test
  fun aFistThatLoosensALittleStaysAFist() {
    val looser = hand(mcp = 65f, pip = 70f, dip = 30f, thumbTip = wrapped)
    assertFalse(FistShape.isFist(looser, wasFist = false))
    assertTrue(FistShape.measure(looser).toString(), FistShape.isFist(looser, wasFist = true))
  }

  @Test
  fun unrollingEndsTheFist() {
    assertFalse(FistShape.isFist(hand(mcp = 30f, pip = 35f, dip = 15f, thumbTip = wrapped), wasFist = true))
  }

  @Test
  fun curlDoesNotDependOnHandOrientation() {
    val fist = hand(mcp = 90f, pip = 100f, dip = 60f, thumbTip = wrapped)
    // Rotate the whole hand 90 degrees about Z and move it: same curl.
    val turned = FloatArray(fist.size)
    for (i in 0 until FistShape.JOINT_COUNT) {
      turned[i * 3] = -fist[i * 3 + 1] + 0.3f
      turned[i * 3 + 1] = fist[i * 3] + 1.2f
      turned[i * 3 + 2] = fist[i * 3 + 2] - 0.4f
    }
    assertEquals(FistShape.measure(fist).mean, FistShape.measure(turned).mean, 0.5f)
    assertTrue(FistShape.isFist(turned, wasFist = false))
  }
}
