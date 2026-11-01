package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low in the periphery at arm's reach: [BELOW_EYES_M] below eye height and [FORWARD_M] ahead in
 * the facing direction (about 42 degrees below the horizon), level and facing the eyes, so a glance
 * down brings it into view and it can be touched directly. Only head position and yaw matter;
 * pitch and roll are ignored so looking down never drags it. [DockFollow] eases the dock toward
 * this target rather than locking it to the head.
 */
internal object DockPlacement {
  /** The expanded dock. Other overlays may stack against this footprint. */
  const val WIDTH_M = 0.66f
  const val HEIGHT_M = 0.095f
  /** Pointer/hand footprint of the collapsed line, which is drawn much thinner (see DockPill). */
  const val PILL_WIDTH_M = 0.16f
  const val PILL_HEIGHT_M = 0.03f
  const val FORWARD_M = 0.45f
  const val BELOW_EYES_M = 0.40f

  /** Where the dock settles for [head]. */
  fun pose(head: Pose): Pose = poseAt(head.t, yawDegrees(head))

  /** The dock for an [eye] position facing [yawDeg] (0 = +Z, positive toward +X). */
  fun poseAt(eye: Vector3, yawDeg: Float): Pose {
    val yaw = Math.toRadians(yawDeg.toDouble())
    val offset = Vector3(sin(yaw).toFloat() * FORWARD_M, -BELOW_EYES_M, cos(yaw).toFloat() * FORWARD_M)
    return Pose(eye + offset, Quaternion.lookRotation(offset))
  }

  /**
   * Horizontal facing direction in degrees. Only when looking nearly straight up or down, where the
   * forward vector has almost no horizontal component, does the head's up vector decide it (the top
   * of the head tilts away from the way the face points); otherwise head roll would leak into yaw.
   */
  fun yawDegrees(head: Pose): Float {
    val forward = head.forward().normalize()
    var flat = Vector3(forward.x, 0f, forward.z)
    if (flat.length() < NEAR_VERTICAL) {
      val up = head.up()
      flat = Vector3(up.x, 0f, up.z) * (if (forward.y > 0f) -1f else 1f)
    }
    if (flat.length() < 0.0001f) return 0f
    return Math.toDegrees(atan2(flat.x, flat.z).toDouble()).toFloat()
  }

  /** Horizontal length of a unit gaze below which it counts as straight up or down (about 78 degrees). */
  private const val NEAR_VERTICAL = 0.2f

  /** Ray/quad intersection with a dock at [dock], also used to keep input off panels behind it. */
  fun hitDistance(dock: Pose, origin: Vector3, direction: Vector3, widthM: Float = WIDTH_M, heightM: Float = HEIGHT_M): Float? {
    if (direction.length() < 0.001f) return null
    val inverse = dock.inverse()
    val localOrigin = inverse.q * origin + inverse.t
    val localDirection = inverse.q * direction.normalize()
    if (localDirection.z <= 0.0001f) return null
    val distance = -localOrigin.z / localDirection.z
    if (distance < 0f) return null
    val hit = localOrigin + localDirection * distance
    return distance.takeIf { abs(hit.x) <= widthM / 2f && abs(hit.y) <= heightM / 2f }
  }

  /** Shortest distance from [point] (a hand or fingertip) to the dock rectangle at [dock]. */
  fun distanceTo(dock: Pose, point: Vector3, widthM: Float = WIDTH_M, heightM: Float = HEIGHT_M): Float {
    val inverse = dock.inverse()
    val local = inverse.q * point + inverse.t
    val dx = (abs(local.x) - widthM / 2f).coerceAtLeast(0f)
    val dy = (abs(local.y) - heightM / 2f).coerceAtLeast(0f)
    return sqrt(dx * dx + dy * dy + local.z * local.z)
  }
}
