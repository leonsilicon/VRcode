package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Like an iPhone's home bar: the dock's bar sits at the bottom of the view, [BELOW_GAZE_DEG] below
 * where the user looks and [DISTANCE_M] from the eyes, so a hand can reach it. It follows yaw and
 * pitch (lazily, see [DockFollow]) but never roll, and always faces the eyes. Swiping up from the
 * bar raises the dock's body above it.
 *
 * Dock-local frame: the bar is at the origin; X to the user's right, Y up (tilted toward the eyes),
 * Z away from the user. The body spans [BODY_BOTTOM_M]..[BODY_TOP_M] above the bar.
 */
internal object DockPlacement {
  const val DISTANCE_M = 0.45f
  const val BELOW_GAZE_DEG = 38f
  private const val MIN_ELEVATION_DEG = -80f
  private const val MAX_ELEVATION_DEG = -15f

  /** The bar as drawn, and its larger touch/pointer target. */
  const val BAR_WIDTH_M = 0.07f
  const val BAR_HIT_WIDTH_M = 0.10f
  const val BAR_HIT_HEIGHT_M = 0.05f

  /** The raised dock: a row of options with a grabber (the bar, moved up) at its top centre. */
  const val BODY_WIDTH_M = DockLayout.MAX_SPAN_M + 0.06f
  const val BODY_BOTTOM_M = 0.02f
  const val BODY_TOP_M = 0.13f
  const val ROW_Y_M = 0.06f
  const val GRAB_Y_M = 0.11f

  /** What other overlays (voice pane, keyboard) stack above: the raised body, centred on the bar. */
  const val WIDTH_M = BODY_WIDTH_M
  const val HEIGHT_M = 2f * BODY_TOP_M

  /** Where the bar settles for [head]. */
  fun pose(head: Pose): Pose = poseAt(head.t, yawDegrees(head), pitchDegrees(head))

  /** The bar for an [eye] looking along [yawDeg] (0 = +Z, positive toward +X) and [pitchDeg] (up positive). */
  fun poseAt(eye: Vector3, yawDeg: Float, pitchDeg: Float): Pose {
    val elevation = Math.toRadians((pitchDeg - BELOW_GAZE_DEG).coerceIn(MIN_ELEVATION_DEG, MAX_ELEVATION_DEG).toDouble())
    val yaw = Math.toRadians(yawDeg.toDouble())
    val flat = DISTANCE_M * cos(elevation).toFloat()
    val offset = Vector3(sin(yaw).toFloat() * flat, DISTANCE_M * sin(elevation).toFloat(), cos(yaw).toFloat() * flat)
    return Pose(eye + offset, Quaternion.lookRotation(offset))
  }

  /** The raised body's centre, for hit tests against it. */
  fun bodyPose(bar: Pose): Pose = bar * Pose(Vector3(0f, (BODY_BOTTOM_M + BODY_TOP_M) / 2f, 0f))

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

  /** Gaze elevation in degrees, up positive. Roll does not change it. */
  fun pitchDegrees(head: Pose): Float = Math.toDegrees(asin(head.forward().normalize().y.coerceIn(-1f, 1f)).toDouble()).toFloat()

  /** Horizontal length of a unit gaze below which it counts as straight up or down (about 78 degrees). */
  private const val NEAR_VERTICAL = 0.2f

  /** Ray/quad intersection with a rectangle centred on [panel], also used to keep input off panels behind it. */
  fun hitDistance(panel: Pose, origin: Vector3, direction: Vector3, widthM: Float, heightM: Float): Float? {
    if (direction.length() < 0.001f) return null
    val inverse = panel.inverse()
    val localOrigin = inverse.q * origin + inverse.t
    val localDirection = inverse.q * direction.normalize()
    if (localDirection.z <= 0.0001f) return null
    val distance = -localOrigin.z / localDirection.z
    if (distance < 0f) return null
    val hit = localOrigin + localDirection * distance
    return distance.takeIf { abs(hit.x) <= widthM / 2f && abs(hit.y) <= heightM / 2f }
  }

  /** Shortest distance from [point] (a hand or fingertip) to the rectangle centred on [panel]. */
  fun distanceTo(panel: Pose, point: Vector3, widthM: Float, heightM: Float): Float {
    val inverse = panel.inverse()
    val local = inverse.q * point + inverse.t
    val dx = (abs(local.x) - widthM / 2f).coerceAtLeast(0f)
    val dy = (abs(local.y) - heightM / 2f).coerceAtLeast(0f)
    return sqrt(dx * dx + dy * dy + local.z * local.z)
  }
}
