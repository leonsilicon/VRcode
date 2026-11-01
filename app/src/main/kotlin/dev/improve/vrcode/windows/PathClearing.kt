package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clear path: the windows in front of the user part to either side, like curtains, so the way
 * ahead is clear. Each window keeps its distance, height and facing relative to the user; it only
 * swings around them. The windows on the right of the user's heading swing right together, those on
 * the left swing left, each group just far enough that its nearest window's inner edge ends
 * [CLEAR_DEG] from straight ahead. Swinging a whole side together keeps its windows apart, in the
 * order they were. Windows already clear of that, and those beside or behind the user, stay put.
 */
internal object PathClearing {
  /** How far from straight ahead the inner edge of the nearest cleared window ends up. */
  const val CLEAR_DEG = 50f
  /** Windows further round than this from straight ahead are beside or behind the user, not in the way. */
  const val FRONT_DEG = 90f

  /** One shown window: its pose and half its width, in metres. */
  class Window<K>(val key: K, val pose: Pose, val halfWidthM: Float)

  /**
   * Where each window that has to move goes, for a user at [eye] facing [forward] (only its heading
   * counts). Windows that stay put are left out.
   */
  fun <K> aside(eye: Vector3, forward: Vector3, windows: List<Window<K>>): Map<K, Pose> {
    val heading = heading(forward)
    val clear = Math.toRadians(CLEAR_DEG.toDouble()).toFloat()
    val front = Math.toRadians(FRONT_DEG.toDouble()).toFloat()
    // Each window in front: its side (+1 right of the heading, -1 left) and how far that side must
    // swing for its inner edge to clear the middle.
    class InFront(val window: Window<K>, val side: Int, val needed: Float)
    val inFront = windows.mapNotNull { window ->
      val offset = window.pose.t - eye
      val radius = sqrt(offset.x * offset.x + offset.z * offset.z)
      if (radius < MIN_RADIUS_M) return@mapNotNull null
      val angle = wrap(atan2(offset.x, offset.z) - heading)
      if (abs(angle) > front) return@mapNotNull null
      InFront(window, if (angle >= 0f) 1 else -1, clear + atan(window.halfWidthM / radius) - abs(angle))
    }
    val swing = mapOf(1 to 0f, -1 to 0f).toMutableMap()
    for (seen in inFront) swing[seen.side] = max(swing.getValue(seen.side), seen.needed)
    val moved = LinkedHashMap<K, Pose>()
    for (seen in inFront) {
      val amount = swing.getValue(seen.side)
      if (amount > 0f) moved[seen.window.key] = swung(seen.window.pose, eye, amount * seen.side)
    }
    return moved
  }

  /** [pose] swung [radians] around the vertical line through [pivot] (positive turns +Z toward +X). */
  fun swung(pose: Pose, pivot: Vector3, radians: Float): Pose {
    val turn = yaw(radians)
    return Pose(pivot + turn.times(pose.t - pivot), turn.times(pose.q))
  }

  /** The heading of [forward] around the vertical axis: 0 along +Z, positive toward +X. */
  fun heading(forward: Vector3): Float {
    val flat = sqrt(forward.x * forward.x + forward.z * forward.z)
    return if (flat > 0.1f) atan2(forward.x, forward.z) else 0f
  }

  /** A rotation of [radians] about the vertical axis, turning +Z toward +X for positive angles. */
  fun yaw(radians: Float): Quaternion = Quaternion.lookRotation(Vector3(sin(radians), 0f, cos(radians)))

  /** [angle] in -pi..pi. */
  fun wrap(angle: Float): Float {
    var a = angle
    while (a > PI) a -= (2 * PI).toFloat()
    while (a < -PI) a += (2 * PI).toFloat()
    return a
  }

  private const val MIN_RADIUS_M = 0.05f
}

/**
 * Windows gliding between poses over [durationMs], swinging around a pivot (the user) rather than
 * cutting straight across, so parting windows keep their distance and keep facing the user on the
 * way. Several glide at once; starting one again replaces it.
 */
internal class WindowGlide<K>(private val durationMs: Long = 450L) {
  private class Run(val from: Pose, val to: Pose, val pivot: Vector3, val startMs: Long)
  private val runs = LinkedHashMap<K, Run>()

  val active: Boolean get() = runs.isNotEmpty()

  fun start(key: K, from: Pose, to: Pose, pivot: Vector3, nowMs: Long) {
    runs[key] = Run(from, to, pivot, nowMs)
  }

  fun cancel(key: K) {
    runs.remove(key)
  }

  fun cancelAll() = runs.clear()

  /** Each gliding window's pose for this frame; a window's last frame lands exactly on its target. */
  fun tick(nowMs: Long): Map<K, Pose> {
    if (runs.isEmpty()) return emptyMap()
    val poses = LinkedHashMap<K, Pose>()
    val done = ArrayList<K>()
    for ((key, run) in runs) {
      val t = ((nowMs - run.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
      if (t >= 1f) {
        poses[key] = run.to
        done += key
        continue
      }
      poses[key] = at(run.from, run.to, run.pivot, t * t * (3f - 2f * t))
    }
    done.forEach { runs.remove(it) }
    return poses
  }

  companion object {
    /** [from] to [to] at [t] (0..1), around [pivot]: angle, horizontal distance and height each eased. */
    fun at(from: Pose, to: Pose, pivot: Vector3, t: Float): Pose {
      val a = from.t - pivot
      val b = to.t - pivot
      val ra = sqrt(a.x * a.x + a.z * a.z)
      val rb = sqrt(b.x * b.x + b.z * b.z)
      val rotation = from.q.slerp(to.q, t)
      // Too close to the pivot for an angle to mean anything: go straight.
      if (ra < 0.05f || rb < 0.05f) return Pose(from.t + (to.t - from.t) * t, rotation)
      val start = atan2(a.x, a.z)
      val angle = start + PathClearing.wrap(atan2(b.x, b.z) - start) * t
      val radius = ra + (rb - ra) * t
      val height = a.y + (b.y - a.y) * t
      return Pose(pivot + Vector3(sin(angle) * radius, height, cos(angle) * radius), rotation)
    }
  }
}
