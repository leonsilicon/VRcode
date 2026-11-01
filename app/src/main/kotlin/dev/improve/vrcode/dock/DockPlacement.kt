package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.DockShelf
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Like an iPhone's home bar: the dock's bar sits at the bottom of the view, [BELOW_GAZE_DEG] below
 * where the user looks and [DISTANCE_M] from the eyes, so a hand can reach it. It follows yaw and
 * pitch (lazily, see [DockFollow]) but never roll, and always faces the eyes. Pinching and swiping
 * the bar pulls up the dock's sheet, like the grabber of an iPhone bottom sheet: the bar is always
 * the sheet's top edge, never apart from it.
 *
 * Dock-local frame: the resting bar is at the origin; X right, Y up (tilted toward the eyes),
 * Z away from the user. Sheet coordinates describe its fully expanded layout.
 */
internal object DockPlacement {
  const val DISTANCE_M = 0.45f
  /** Low in the view, below where hands drag windows, yet above the Quest 3's lower edge (~48°). */
  const val BELOW_GAZE_DEG = 44f
  private const val MIN_ELEVATION_DEG = -80f
  private const val MAX_ELEVATION_DEG = -15f

  /** The bar as drawn, and its larger touch/pointer target (the bar's panel). */
  const val BAR_WIDTH_M = 0.07f
  const val BAR_HIT_WIDTH_M = 0.12f
  const val BAR_HIT_HEIGHT_M = 0.06f
  /** The bar's panel overlaps the sheet's top; it stands this far in front so rays meet it first. */
  const val BAR_FORWARD_M = 0.003f

  /**
   * The sheet: one rounded body with the bar inset at its top edge, like an iPhone bottom sheet's
   * grabber. Its bottom edge stays at [SHEET_BOTTOM_M], just below where the bar rests, and its top
   * rises with the bar, so the sheet grows out of the bar's resting place and its contents ride up
   * with the bar. Expanded, it spans [SHEET_BOTTOM_M]..[SHEET_TOP_M].
   */
  const val BODY_WIDTH_M = DockLayout.MAX_SPAN_M + 0.06f
  const val SHEET_BOTTOM_M = -0.01f
  /** The bar's centre below the sheet's top edge. */
  const val GRABBER_INSET_M = 0.02f
  /** A full-size icon tile (at [DockLayout.MAX_SPACING_M]); tiles shrink with the spacing when the row is full. */
  const val TILE_M = DockShelf.TILE * DockLayout.MAX_SPACING_M
  /** The icons' centres. */
  const val ROW_Y_M = 0.03f
  /** The top of a full-size tile magnified under a fingertip, which grows up from its bottom edge. */
  const val MAGNIFIED_TOP_M = ROW_Y_M - TILE_M / 2f + TILE_M * DockShelf.MAGNIFY
  /**
   * The hovered option's label (its centre), inside the sheet between the row and the bar: clear of
   * a magnified icon below it and of the bar's panel (its bottom at 8.5 cm) above it.
   */
  const val LABEL_Y_M = 0.076f
  /** Half the label tooltip's height, about 1.4 cm tall at the panels' pixel scale. */
  const val LABEL_HALF_HEIGHT_M = 0.007f

  /** Full travel of the bar: from rest to the expanded sheet's top, 2 cm below that edge. */
  const val TRAVEL_M = 0.115f
  const val SHEET_TOP_M = TRAVEL_M + GRABBER_INSET_M
  const val SHEET_HEIGHT_M = SHEET_TOP_M - SHEET_BOTTOM_M
  const val SHEET_CENTER_M = (SHEET_TOP_M + SHEET_BOTTOM_M) / 2f

  /** The sheet is fully opaque by this much of the swipe, so it never pops in around the resting bar. */
  private const val FADE_LIFT = 0.2f

  fun handleY(lift: Float): Float = TRAVEL_M * lift.coerceIn(0f, 1f)

  /** The sheet's top edge, which carries the bar, at [lift]. */
  fun sheetTop(lift: Float): Float = handleY(lift) + GRABBER_INSET_M

  /** How far the sheet's top, and the options riding with it, sit below their expanded place. */
  fun sheetDrop(lift: Float): Float = SHEET_TOP_M - sheetTop(lift)

  fun sheetOpacity(lift: Float): Float = (lift / FADE_LIFT).coerceIn(0f, 1f)

  fun handlePose(base: Pose, lift: Float): Pose = base * Pose(Vector3(0f, handleY(lift), -BAR_FORWARD_M))

  /** The sheet's panel: the size of the expanded sheet and fixed to the dock; the drawn sheet grows inside it. */
  fun sheetPanelPose(base: Pose): Pose = base * Pose(Vector3(0f, SHEET_CENTER_M, 0f))

  /** The visible sheet's height at [lift]. */
  fun sheetHeight(lift: Float): Float = sheetTop(lift) - SHEET_BOTTOM_M

  /** The visible sheet's centre at [lift], for hit tests against it. */
  fun sheetPose(base: Pose, lift: Float = 1f): Pose = base * Pose(Vector3(0f, (sheetTop(lift) + SHEET_BOTTOM_M) / 2f, 0f))

  /**
   * A touch on the bar's or the sheet's panel, given as fractions of that panel's width and height
   * from its top-left corner, in dock-local metres (X right, Y up from the resting bar).
   */
  fun touchPoint(surface: DockGesture.Surface, fx: Float, fy: Float): Pair<Float, Float> =
    when (surface) {
      DockGesture.Surface.Bar -> (fx - 0.5f) * BAR_HIT_WIDTH_M to (0.5f - fy) * BAR_HIT_HEIGHT_M
      DockGesture.Surface.Body -> (fx - 0.5f) * BODY_WIDTH_M to SHEET_TOP_M - fy * SHEET_HEIGHT_M
    }

  /** What other overlays (voice pane, keyboard) stack above: the expanded dock, centred on the bar. */
  const val WIDTH_M = BODY_WIDTH_M
  const val HEIGHT_M = 2f * (TRAVEL_M + BAR_HIT_HEIGHT_M / 2f)

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
