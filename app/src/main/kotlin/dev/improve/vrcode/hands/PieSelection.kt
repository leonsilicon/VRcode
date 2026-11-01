package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

/**
 * Picks a palm menu item from the hand's movement in scene space. The menu's frame is captured
 * when it opens, upright and facing the head, so wrist rotation and head movement afterwards do
 * not move the selection axes. Spatial's scene is left-handed (+X right, +Y up, +Z forward).
 */
class PieSelection(val menu: RadialMenu) {
  /** Where the menu opened (the pinch point), or null while closed. */
  var center: Vector3? = null
    private set

  /** Toward the head-facing menu's right and up. */
  var right = Vector3(1f, 0f, 0f)
    private set
  var forward = Vector3(0f, 0f, 1f)
    private set
  val up = Vector3(0f, 1f, 0f)

  /** The item the hand moves toward, or null in the dead zone. */
  var highlighted: Int? = null
    private set

  /** The highlighted item once the hand reaches it; the caller runs it at once. */
  var reached: Int? = null
    private set

  /** How far the hand has moved from [center] in the menu's plane, in metres. */
  var distanceM = 0f
    private set

  fun open(at: Vector3, head: Pose) {
    val toward = at - head.t
    val flat = Vector3(toward.x, 0f, toward.z)
    val headForward = head.forward()
    val fallback = Vector3(headForward.x, 0f, headForward.z)
    forward = when {
      flat.length() > 0.05f -> flat.normalize()
      fallback.length() > 0.1f -> fallback.normalize()
      else -> Vector3(0f, 0f, 1f)
    }
    // Left-handed: up x forward points right (forward x up would point left).
    right = up.cross(forward).normalize()
    center = at
    highlighted = null
    reached = null
    distanceM = 0f
  }

  /** Feeds the pinch point's scene position while the menu is open. */
  fun update(point: Vector3) {
    val c = center ?: return
    val d = point - c
    val r = d.dot(right)
    val u = d.dot(up)
    distanceM = kotlin.math.sqrt(r * r + u * u)
    highlighted = menu.pick(r, u)
    reached = highlighted?.takeIf { menu.reached(r, u) }
  }

  fun close() {
    center = null
    highlighted = null
    reached = null
    distanceM = 0f
  }
}

/**
 * A point in OpenXR VIEW space (right-handed, -Z forward) as a Spatial scene position, given the
 * head's scene pose for the same frame. VIEW and Spatial's head frame share +X right and +Y up; only
 * Z is reflected, as in [SceneAlignment].
 */
fun viewToScene(head: Pose, p: FloatArray): Vector3 = head.t + head.q * Vector3(p[0], p[1], -p[2])
