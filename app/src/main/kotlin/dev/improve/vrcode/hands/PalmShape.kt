package dev.improve.vrcode.hands

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Palm orientation and pinch geometry from OpenXR hand joints (XR_EXT_hand_tracking order) in VIEW
 * space, where the head is the origin. Works for either hand without mirroring.
 *
 * The palm's normal comes from the runtime's palm joint orientation: the OpenXR spec defines its +Y
 * as "perpendicular to palm surface and pointing towards the back of the hand", so -Y points out of
 * the palm. Palm-facing is then MRTK's palm-up hand menu test: the angle between that normal and
 * the direction from the palm to the head.
 */
object PalmShape {
  const val PALM = 0
  const val WRIST = 1
  const val THUMB_TIP = 5
  const val INDEX_TIP = 10
  const val MIDDLE_TIP = 15

  /** Unit normal pointing out of the palm: the palm joint's -Y axis rotated by [palm] (x, y, z, w). */
  fun normal(palm: FloatArray): FloatArray = normalize(rotate(palm, floatArrayOf(0f, -1f, 0f)))

  /** Angle between the palm's normal and the direction from the palm to the head, in degrees. */
  fun facingDegrees(joints: FloatArray, palm: FloatArray): Float {
    val toHead = normalize(scale(point(joints, PALM), -1f))
    return Math.toDegrees(acos(dot(normal(palm), toHead).coerceIn(-1f, 1f).toDouble())).toFloat()
  }

  /** Distance from the thumb tip to the fingertip joint [tip]. */
  fun thumbToTipM(joints: FloatArray, tip: Int): Float = distance(joints, THUMB_TIP, tip)

  /**
   * Whether the thumb tip and [tip] touch: their skin surfaces (each joint's runtime-reported
   * [radii]) are within [slackM]. Used only when the runtime withholds its own pinch signals.
   */
  fun touching(joints: FloatArray, radii: FloatArray, tip: Int, slackM: Float): Boolean =
    distance(joints, THUMB_TIP, tip) <= radii[THUMB_TIP] + radii[tip] + slackM

  /**
   * Where a hint sits on the finger ending at joint [tipJoint]: [backM] back from the fingertip
   * toward its distal joint, and [outM] off the palm toward the user, onto the finger's pad. In
   * XR_EXT_hand_tracking order each fingertip's distal joint comes just before it.
   */
  fun onFingertip(joints: FloatArray, palm: FloatArray, tipJoint: Int, backM: Float, outM: Float): FloatArray {
    val tip = point(joints, tipJoint)
    val along = normalize(sub(tip, point(joints, tipJoint - 1)))
    val out = normal(palm)
    return floatArrayOf(
      tip[0] - along[0] * backM + out[0] * outM,
      tip[1] - along[1] * backM + out[1] * outM,
      tip[2] - along[2] * backM + out[2] * outM,
    )
  }

  /** Midway between the thumb tip and the fingertip joint [tip]. */
  fun pinchPoint(joints: FloatArray, tip: Int): FloatArray {
    val a = point(joints, THUMB_TIP)
    val b = point(joints, tip)
    return floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, (a[2] + b[2]) / 2f)
  }

  /** [v] rotated by the unit quaternion [q] (x, y, z, w): v + 2w(u x v) + 2u x (u x v). */
  fun rotate(q: FloatArray, v: FloatArray): FloatArray {
    val u = floatArrayOf(q[0], q[1], q[2])
    val w = q[3]
    val uv = cross(u, v)
    val uuv = cross(u, uv)
    return floatArrayOf(
      v[0] + 2f * (w * uv[0] + uuv[0]),
      v[1] + 2f * (w * uv[1] + uuv[1]),
      v[2] + 2f * (w * uv[2] + uuv[2]),
    )
  }

  private fun point(p: FloatArray, i: Int) = floatArrayOf(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])

  private fun distance(p: FloatArray, a: Int, b: Int): Float {
    val d = sub(point(p, a), point(p, b))
    return sqrt(dot(d, d))
  }

  private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

  private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)

  private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

  private fun cross(a: FloatArray, b: FloatArray) =
    floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

  private fun normalize(a: FloatArray): FloatArray {
    val l = sqrt(dot(a, a))
    return if (l < 1e-6f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
  }
}
