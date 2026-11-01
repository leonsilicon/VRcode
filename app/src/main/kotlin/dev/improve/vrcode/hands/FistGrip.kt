package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.sqrt

/**
 * Where a handheld prop sits in a closed fist, from OpenXR hand joints (XR_EXT_hand_tracking
 * order), so it follows the fist as it moves and turns.
 *
 * It approximates OpenXR's grip pose (the axis of a cylinder held in the fist). The Spatial SDK
 * gives the hand entity only the aim (pointing) pose, which is filtered for pointing, sits at the
 * pinch rather than in the fist, and does not turn with the wrist; it has no per-joint hand poses
 * outside full body tracking. Meta's Interaction SDK hand grab, which does this in Unity and
 * Unreal, likewise anchors held objects to the tracked hand skeleton.
 *
 * In the returned frame:
 * - the origin is the hollow the curled fingers wrap around: the mean of each finger's knuckle,
 *   middle and end joints and the middle of its palm bone;
 * - +Y runs through that hollow from the little finger to the index finger, out of the
 *   thumb-and-index opening (a microphone's grille end);
 * - +Z points back toward the wrist (a megaphone's mouthpiece), perpendicular to +Y;
 * - +X completes a right-handed frame.
 *
 * The props' GLBs share this convention (grip node at the origin, +Y toward the speech end).
 * [SceneAlignment] converts this OpenXR pose to Spatial's left-handed scene coordinates before
 * applying it to the prop, matching the SDK's glTF conversion. It works for either hand.
 */
object FistGrip {
  const val WRIST = 1
  private const val INDEX = 0
  private const val MIDDLE = 1
  private const val LITTLE = 3

  /** The grip pose in the joints' space, or null when the joints are degenerate. */
  fun pose(joints: FloatArray): Pose? {
    if (joints.size < FistShape.JOINT_COUNT * 3) return null
    var center = Vector3(0f)
    var count = 0
    for (finger in FistShape.FINGERS) {
      center += (at(joints, finger[0]) + at(joints, finger[1])) * 0.5f // middle of the palm bone
      for (k in 1..3) center += at(joints, finger[k]) // knuckle, middle and end joints
      count += 4
    }
    center *= 1f / count

    // Along the knuckle row, little finger to index finger, from the knuckle and middle joints.
    val index = FistShape.FINGERS[INDEX]
    val little = FistShape.FINGERS[LITTLE]
    val axis = normalized(at(joints, index[1]) + at(joints, index[2]) - at(joints, little[1]) - at(joints, little[2])) ?: return null
    // Wrist to the middle finger's knuckle is the fist's forward; +Z is its opposite.
    val forward = at(joints, FistShape.FINGERS[MIDDLE][1]) - at(joints, WRIST)
    val back = normalized(axis * axis.dot(forward) - forward) ?: return null
    return Pose(center, fromAxes(axis.cross(back), axis, back))
  }

  /** The rotation whose local X, Y and Z axes are the orthonormal [x], [y], [z]. */
  fun fromAxes(x: Vector3, y: Vector3, z: Vector3): Quaternion {
    // Rotation matrix columns are the axes: m[row][col].
    val m00 = x.x
    val m10 = x.y
    val m20 = x.z
    val m01 = y.x
    val m11 = y.y
    val m21 = y.z
    val m02 = z.x
    val m12 = z.y
    val m22 = z.z
    val trace = m00 + m11 + m22
    val q =
      when {
        trace > 0f -> {
          val s = 2f * sqrt(trace + 1f)
          Quaternion(0.25f * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
        }
        m00 > m11 && m00 > m22 -> {
          val s = 2f * sqrt(1f + m00 - m11 - m22)
          Quaternion((m21 - m12) / s, 0.25f * s, (m01 + m10) / s, (m02 + m20) / s)
        }
        m11 > m22 -> {
          val s = 2f * sqrt(1f + m11 - m00 - m22)
          Quaternion((m02 - m20) / s, (m01 + m10) / s, 0.25f * s, (m12 + m21) / s)
        }
        else -> {
          val s = 2f * sqrt(1f + m22 - m00 - m11)
          Quaternion((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25f * s)
        }
      }
    return q.normalize()
  }

  private fun at(p: FloatArray, joint: Int) = Vector3(p[joint * 3], p[joint * 3 + 1], p[joint * 3 + 2])

  private fun normalized(v: Vector3): Vector3? {
    val length = v.length()
    return if (length < 1e-4f) null else v * (1f / length)
  }
}
