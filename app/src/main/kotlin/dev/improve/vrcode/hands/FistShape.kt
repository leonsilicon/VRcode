package dev.improve.vrcode.hands

import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A strict fist from OpenXR hand joints (XR_EXT_hand_tracking order): all four fingers rolled up,
 * not merely cupped, and the thumb wrapped over them. The runtime's own fist flag (the microgesture
 * pose) also fires for a loosely cupped hand, so voice mode does not use it when joints are
 * available.
 *
 * A finger's curl is the sum of its three bends (knuckle, middle joint, end joint) in degrees:
 * about 0 when straight, roughly 90-130 when cupped and 220-280 when rolled into a fist. It does
 * not depend on hand size or orientation. Entering needs more than staying in (hysteresis), so a
 * fist that loosens slightly while talking stays a fist.
 */
object FistShape {
  const val JOINT_COUNT = 26
  const val THUMB_TIP = 5

  /** Metacarpal, proximal, intermediate, distal, tip for index, middle, ring and little. */
  val FINGERS: List<IntArray> =
    listOf(intArrayOf(6, 7, 8, 9, 10), intArrayOf(11, 12, 13, 14, 15), intArrayOf(16, 17, 18, 19, 20), intArrayOf(21, 22, 23, 24, 25))

  /** Index and middle middle/end joints: where a wrapped thumb rests. */
  private val THUMB_REST = intArrayOf(8, 9, 13, 14)

  const val ENTER_EACH_DEGREES = 150f
  const val ENTER_MEAN_DEGREES = 185f
  const val EXIT_MEAN_DEGREES = 140f
  const val THUMB_ENTER_M = 0.05f
  const val THUMB_EXIT_M = 0.07f

  class Measure(val curls: FloatArray, val thumbM: Float) {
    val mean: Float get() = curls.average().toFloat()
    val least: Float get() = curls.min()

    override fun toString(): String = "curl=${curls.joinToString(",") { it.toInt().toString() }} thumb=${(thumbM * 100).toInt()}cm"
  }

  fun measure(joints: FloatArray): Measure =
    Measure(FloatArray(FINGERS.size) { curlDegrees(joints, FINGERS[it]) }, thumbWrapM(joints))

  /** Whether [joints] is a fist, given whether the previous frame was one. */
  fun isFist(joints: FloatArray, wasFist: Boolean): Boolean = isFist(measure(joints), wasFist)

  fun isFist(m: Measure, wasFist: Boolean): Boolean =
    if (wasFist) {
      m.mean >= EXIT_MEAN_DEGREES && m.thumbM <= THUMB_EXIT_M
    } else {
      m.least >= ENTER_EACH_DEGREES && m.mean >= ENTER_MEAN_DEGREES && m.thumbM <= THUMB_ENTER_M
    }

  fun curlDegrees(joints: FloatArray, finger: IntArray): Float {
    var total = 0f
    for (k in 0 until finger.size - 2) total += bendDegrees(joints, finger[k], finger[k + 1], finger[k + 2])
    return total
  }

  /** Distance from the thumb tip to the nearest resting joint on the index or middle finger. */
  fun thumbWrapM(joints: FloatArray): Float {
    var best = Float.MAX_VALUE
    for (j in THUMB_REST) best = min(best, distance(joints, THUMB_TIP, j))
    return best
  }

  /** The angle between bone a->b and bone b->c, in degrees (0 when they are in line). */
  private fun bendDegrees(p: FloatArray, a: Int, b: Int, c: Int): Float {
    val ux = p[b * 3] - p[a * 3]
    val uy = p[b * 3 + 1] - p[a * 3 + 1]
    val uz = p[b * 3 + 2] - p[a * 3 + 2]
    val vx = p[c * 3] - p[b * 3]
    val vy = p[c * 3 + 1] - p[b * 3 + 1]
    val vz = p[c * 3 + 2] - p[b * 3 + 2]
    val lu = sqrt(ux * ux + uy * uy + uz * uz)
    val lv = sqrt(vx * vx + vy * vy + vz * vz)
    if (lu < 1e-5f || lv < 1e-5f) return 0f
    val cos = ((ux * vx + uy * vy + uz * vz) / (lu * lv)).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cos).toDouble()).toFloat()
  }

  private fun distance(p: FloatArray, a: Int, b: Int): Float {
    val dx = p[a * 3] - p[b * 3]
    val dy = p[a * 3 + 1] - p[b * 3 + 1]
    val dz = p[a * 3 + 2] - p[b * 3 + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
  }
}
