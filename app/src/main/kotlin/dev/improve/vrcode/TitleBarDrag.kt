package dev.improve.vrcode

import androidx.compose.ui.geometry.Offset
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pointer coordinates are relative to the moving panel. Keep the original grab point under the
 * pointer by applying its current displacement to the live pose, rather than accumulating deltas
 * between events (which would count the panel's own movement as pointer movement).
 *
 * With a [head] position, the drag instead sweeps the window around the user: the grab point
 * ([anchor], measured from the panel's [centre]) keeps its distance from the head and moves to
 * where the pointer crosses that sphere, and the window turns upright about it to face the head,
 * the way `WindowLayout` places windows. A window dragged sideways, up or down therefore never
 * ends up edge-on or drifts nearer or further away. Sliding across a head-facing plane instead
 * would change the distance with every step whenever the grab point is off-centre.
 */
internal fun titleBarDragPose(
  pose: Pose,
  anchor: Offset,
  position: Offset,
  centre: Offset,
  metresPerPixelX: Float,
  metresPerPixelY: Float,
  head: Vector3?,
): Pose {
  fun local(point: Offset, origin: Offset) = Vector3(
    (point.x - origin.x) * metresPerPixelX,
    (origin.y - point.y) * metresPerPixelY,
    0f,
  )
  val translated = Pose(pose.t + pose.q.times(local(position, anchor)), pose.q)
  if (head == null) return translated
  val grab = local(anchor, centre)
  val radius = (pose.t + pose.q.times(grab) - head).length()
  // Where the pointer ray crosses the panel now, seen from the head.
  val towardPointer = pose.t + pose.q.times(local(position, centre)) - head
  if (towardPointer.length() < MIN_DISTANCE_M) return translated
  val target = head + towardPointer.normalize() * radius
  val facing = facingWithGrabAt(target - head, grab) ?: return translated
  return Pose(target - facing.times(grab), facing)
}

/**
 * The upright rotation whose panel faces the head (its centre straight ahead along local +Z, the
 * visible face being local -Z) while the panel point [grab] lies at [fromHead]. In that rotation's
 * frame, head-to-grab is (grab.x, grab.y, depth), so this solves pitch, then yaw, in closed form.
 * An iterative solve would diverge for wide panels, whose grab offset rivals the viewing distance.
 * Returns null when no upright facing exists: the head inside the grab radius, or near-vertical.
 */
private fun facingWithGrabAt(fromHead: Vector3, grab: Vector3): Quaternion? {
  val withoutX = fromHead.dot(fromHead) - grab.x * grab.x
  val depthSquared = withoutX - grab.y * grab.y
  if (depthSquared <= 0f) return null
  val depth = sqrt(depthSquared)
  // grab.y * cos(pitch) + depth * sin(pitch) must reach the target's height.
  val pitch = asin((fromHead.y / sqrt(withoutX)).coerceIn(-1f, 1f)) - atan2(grab.y, depth)
  if (cos(pitch) < MIN_COS_PITCH) return null
  // Horizontally, yaw turns (grab.x, reach) onto the target's (x, z).
  val reach = depth * cos(pitch) - grab.y * sin(pitch)
  val yaw = atan2(fromHead.x, fromHead.z) - atan2(grab.x, reach)
  return Quaternion.lookRotation(Vector3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)))
}

/** About 84 degrees: closer to straight up or down, the facing's yaw is unstable. */
private const val MIN_COS_PITCH = 0.1f
private const val MIN_DISTANCE_M = 0.01f
