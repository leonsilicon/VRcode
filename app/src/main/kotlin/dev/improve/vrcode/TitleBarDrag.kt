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
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One drag, sampled from its holding hand once per scene frame. Lateral motion and push/pull use
 * the same ray and sphere solve. Neither a moving panel's pose nor its delayed Compose pointer
 * coordinates feed back into the next placement: transforming old panel hits by a newer pose
 * makes a stationary hand shake the window, and a separate depth writer fights that correction.
 * The panel's scale and grabbed point stay fixed for the duration of the physical press.
 */
internal class TitleBarRayDrag(
  pose: Pose,
  val anchor: Offset,
  private val centre: Offset,
  private val metresPerPixelX: Float,
  private val metresPerPixelY: Float,
  head: Vector3,
  handOrigin: Vector3,
) {
  var radius = (titleBarGrabPoint(pose, anchor, centre, metresPerPixelX, metresPerPixelY) - head).length()
    private set
  private val depth = DragDepth(radius, handOrigin - head)
  private val reach = panelOffset(anchor, centre, metresPerPixelX, metresPerPixelY).length()

  fun move(head: Vector3, rayOrigin: Vector3, rayDirection: Vector3): Pose? {
    val length = rayDirection.length()
    // A missing tracking sample must not poison the integrated reach for the rest of the pinch.
    if (!head.finite() || !rayOrigin.finite() || !(length > 0f && length.isFinite())) return null
    val handFromHead = rayOrigin - head
    val nearest = max(MIN_DISTANCE_M, max(reach + EDGE_CLEARANCE_M, handFromHead.length() + HAND_CLEARANCE_M))
    val wanted = depth.radius(handFromHead, rayDirection, nearest, MAX_DISTANCE_M)
    val moved = titleBarDepthPose(anchor, centre, metresPerPixelX, metresPerPixelY,
      head, rayOrigin, rayDirection, wanted) ?: return null
    radius = wanted
    return moved
  }

  private companion object {
    /** Distances from the head, widened to include an already nearer or further starting window. */
    const val MIN_DISTANCE_M = 0.45f
    const val MAX_DISTANCE_M = 4f
    /** Keep the hand inside the grab sphere and leave room to face a wide panel toward the head. */
    const val HAND_CLEARANCE_M = 0.05f
    const val EDGE_CLEARANCE_M = 0.15f
  }
}

/** A fresh hand ray's crossing of the panel, in pixels; used once to grab without a stale-event jump. */
internal fun titleBarRayPixel(
  pose: Pose, centre: Offset, metresPerPixelX: Float, metresPerPixelY: Float,
  rayOrigin: Vector3, rayDirection: Vector3,
): Offset? {
  val normal = pose.q.times(Vector3(0f, 0f, 1f))
  val facing = normal.dot(rayDirection)
  if (!facing.isFinite() || abs(facing) < 1e-6f) return null
  val distance = normal.dot(pose.t - rayOrigin) / facing
  if (!(distance > 0f && distance.isFinite())) return null
  val local = pose.q.inverse().times(rayOrigin + rayDirection * distance - pose.t)
  val point = Offset(centre.x + local.x / metresPerPixelX, centre.y - local.y / metresPerPixelY)
  return point.takeIf { it.x.isFinite() && it.y.isFinite() }
}

/** The grabbed panel point ([anchor], panel pixels) in world space. */
internal fun titleBarGrabPoint(pose: Pose, anchor: Offset, centre: Offset, metresPerPixelX: Float, metresPerPixelY: Float): Vector3 =
  pose.t + pose.q.times(panelOffset(anchor, centre, metresPerPixelX, metresPerPixelY))

/**
 * A title-bar drag's push or pull: the window placed so that its grab point ([anchor]) lies on the
 * dragging hand's ray ([rayOrigin] along [rayDirection]) at [radius] from the [head], upright and
 * facing the head. The grab point is where the ray leaves the sphere of that radius, so the hand
 * must be inside it: a window comes no closer than the hand.
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
  val length = rayDirection.length()
  if (!head.finite() || !rayOrigin.finite() || !(length > 0f && length.isFinite() && radius > 0f && radius.isFinite())) return null
  val direction = rayDirection * (1f / length)
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

private fun Vector3.finite() = x.isFinite() && y.isFinite() && z.isFinite()

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
