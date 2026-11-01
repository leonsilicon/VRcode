package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

/**
 * Picks a palm menu item from the hand's movement relative to the user's body. The menu's frame is
 * captured when it opens, upright and facing the head, so wrist rotation and head turns afterwards
 * do not move the selection axes. Its centre then travels with the body ([neckPoint], see
 * [follow]): walking, stepping aside or bobbing while pinched carries the menu along instead of
 * reading as hand movement. Turning the whole body still swings the hand across the menu, as
 * swinging the arm would. Spatial's scene is left-handed (+X right, +Y up, +Z forward).
 */
class PieSelection(val menu: RadialMenu) {
  /** Where the menu is: the pinch point it opened at, carried along with the body. Null while closed. */
  var center: Vector3? = null
    private set

  /** [center] relative to [neckPoint], fixed from [open] to [close]. */
  private var fromNeck: Vector3? = null

  /** Toward the head-facing menu's right and up. */
  var right = Vector3(1f, 0f, 0f)
    private set
  var forward = Vector3(0f, 0f, 1f)
    private set
  val up = Vector3(0f, 1f, 0f)

  /** The item the hand moves toward, or null in the dead zone. */
  var highlighted: Int? = null
    private set

  /** The highlighted item once the hand reaches it: its hover begins ([MenuDwell]). */
  var reached: Int? = null
    private set

  /** How far the hand has moved from [center] in the menu's plane, in metres. */
  var distanceM = 0f
    private set

  /** The hand's last displacement from [center] in the menu's plane, in metres. */
  private var rightM = 0f
  private var upM = 0f

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
    fromNeck = at - neckPoint(head)
    highlighted = null
    reached = null
    distanceM = 0f
    rightM = 0f
    upM = 0f
  }

  /**
   * Moves [center] with the body to where it is in [head]'s frame; call once a frame while open,
   * before [update]. Only the head's translation beyond turning about the neck moves it.
   */
  fun follow(head: Pose) {
    val offset = fromNeck ?: return
    center = neckPoint(head) + offset
  }

  /** Feeds the pinch point's scene position while the menu is open. */
  fun update(point: Vector3) {
    val c = center ?: return
    val d = point - c
    val r = d.dot(right)
    val u = d.dot(up)
    rightM = r
    upM = u
    distanceM = kotlin.math.sqrt(r * r + u * u)
    highlighted = menu.pick(r, u)
    reached = highlighted?.takeIf { menu.reached(r, u) }
  }

  /** How far the hand has gone in [item]'s direction from [center], in metres: a swipe through it. */
  fun along(item: Int): Float = menu.along(item, rightM, upM)

  fun close() {
    center = null
    fromNeck = null
    highlighted = null
    reached = null
    distanceM = 0f
    rightM = 0f
    upM = 0f
  }
}

/**
 * A point that moves with the user's body but not with head turns: the pivot of the classic VR
 * neck model, below and behind the eyes in [head]'s frame (LibOVR's default neck-to-eye offsets,
 * 7.5 cm up and 8.05 cm forward). Nodding or turning the head swings the eyes about this point
 * without moving it, while walking carries it along with the torso and the arms. A pinch point
 * and this point come from the same head pose, so their difference does not depend on the head's
 * tracked position at all: lag in it cannot leak into a selection while walking.
 */
fun neckPoint(head: Pose): Vector3 = head.t + head.q * EYES_TO_NECK

/** From the eyes to the neck pivot in the head's frame (+Y up, +Z forward); a fresh copy each time. */
val EYES_TO_NECK: Vector3 get() = Vector3(0f, -0.075f, -0.0805f)

/**
 * A point in OpenXR VIEW space (right-handed, -Z forward) as a Spatial scene position, given the
 * head's scene pose for the same frame. VIEW and Spatial's head frame share +X right and +Y up; only
 * Z is reflected, as in [SceneAlignment].
 */
fun viewToScene(head: Pose, p: FloatArray): Vector3 = head.t + head.q * Vector3(p[0], p[1], -p[2])
