package dev.improve.vrcode

import androidx.compose.ui.geometry.Offset
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
  fun local(point: Offset, origin: Offset) = panelOffset(point, origin, metresPerPixelX, metresPerPixelY)
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

/** The grabbed panel point ([anchor], panel pixels) in world space. */
internal fun titleBarGrabPoint(pose: Pose, anchor: Offset, centre: Offset, metresPerPixelX: Float, metresPerPixelY: Float): Vector3 =
  pose.t + pose.q.times(panelOffset(anchor, centre, metresPerPixelX, metresPerPixelY))

/**
 * A title-bar drag's push or pull: the window placed so that its grab point ([anchor]) lies on the
 * dragging hand's ray ([rayOrigin] along [rayDirection]) at [radius] from the [head], upright and
 * facing the head like [titleBarDragPose] leaves it. The grab point is where the ray leaves the
 * sphere of that radius, so the hand must be inside it: a window comes no closer than the hand.
 * Null when no such placement exists (the ray misses the sphere, or no upright facing does).
 */
internal fun titleBarDepthPose(
  anchor: Offset,
  centre: Offset,
  metresPerPixelX: Float,
  metresPerPixelY: Float,
  head: Vector3,
  rayOrigin: Vector3,
  rayDirection: Vector3,
  radius: Float,
): Pose? {
  if (!(rayDirection.length() > 0f)) return null
  val direction = rayDirection.normalize()
  val fromHead = rayOrigin - head
  val along = fromHead.dot(direction)
  val discriminant = along * along - (fromHead.dot(fromHead) - radius * radius)
  if (discriminant < 0f) return null
  val distance = -along + sqrt(discriminant)
  if (!(distance > 0f)) return null
  val target = rayOrigin + direction * distance
  val grab = panelOffset(anchor, centre, metresPerPixelX, metresPerPixelY)
  val facing = facingWithGrabAt(target - head, grab) ?: return null
  return Pose(target - facing.times(grab), facing)
}

/**
 * Push and pull during one title-bar drag. Moving the dragging hand along the direction it points,
 * measured relative to the head so walking or leaning with the hand does not count, scales the grab
 * point's distance from the head exponentially: [GAIN] per metre, so the same reach moves a far
 * window further than a near one, and pushing then pulling back returns it. Sweeping the hand
 * sideways moves it across the ray, which barely counts, and nothing changes until the hand has
 * travelled [ENGAGE_M] along it, so an ordinary drag stays at its distance. Motion is summed frame
 * by frame along the current ray, so a sweep that turns the ray is not mistaken for reach.
 */
internal class DragDepth(private val startRadius: Float, handFromHead: Vector3) {
  private var previous = handFromHead
  private var travel = 0f
  /** Travel that maps to [startRadius]; set when depth engages, and moved to absorb a clamp. */
  private var origin: Float? = null

  /** The grab point's distance from the head now, kept within [min]..[max] (widened to include the start). */
  fun radius(handFromHead: Vector3, direction: Vector3, min: Float, max: Float): Float {
    if (direction.length() > 0f) travel += (handFromHead - previous).dot(direction.normalize())
    previous = handFromHead
    val base = origin ?: run {
      if (abs(travel) < ENGAGE_M) return startRadius
      // Only the threshold is ignored: engaging starts from the current distance, without a jump.
      (if (travel > 0f) ENGAGE_M else -ENGAGE_M).also { origin = it }
    }
    val wanted = startRadius * exp(GAIN * (travel - base))
    val clamped = wanted.coerceIn(minOf(min, startRadius), maxOf(max, startRadius))
    // Past a limit, reaching back moves the window again at once rather than after undoing the overshoot.
    if (clamped != wanted) origin = travel - ln(clamped / startRadius) / GAIN
    return clamped
  }

  companion object {
    /** About 1.4x per 10 cm, 2.4x per 25 cm (a comfortable reach), either way. */
    const val GAIN = 3.5f
    const val ENGAGE_M = 0.03f
  }
}

private fun panelOffset(point: Offset, origin: Offset, metresPerPixelX: Float, metresPerPixelY: Float) = Vector3(
  (point.x - origin.x) * metresPerPixelX,
  (origin.y - point.y) * metresPerPixelY,
  0f,
)

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
