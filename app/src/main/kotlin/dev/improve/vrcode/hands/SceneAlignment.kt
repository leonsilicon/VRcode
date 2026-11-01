package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3

/**
 * Maps poses from vrcode's own OpenXR LOCAL space (where [LeftHandJoints] locates the hand) into
 * the Spatial SDK scene, which uses its own reference space (LOCAL_FLOOR under xmux) and view
 * origin. OpenXR is right-handed (-Z forward), while Spatial is left-handed (+Z forward).
 * Convert that handedness for both the head and hand before applying the fixed yaw and offset
 * measured from the head poses. Small frame-timing differences between those poses are smoothed
 * out, since the true offset only changes on a recenter, which is applied at once.
 */
class SceneAlignment {
  /** Scene-from-left-handed-LOCAL, or null until the head has been seen in both spaces. */
  var offset: Pose? = null
    private set

  fun update(headInLocal: Pose, headInScene: Pose) {
    val local = toLeftHanded(headInLocal)
    val q = (headInScene.q * local.q.inverse()).removePitchAndRoll().normalize()
    val measured = Pose(headInScene.t - q * local.t, q)
    val current = offset
    offset =
      if (current == null || current.t.distanceTo(measured.t) > SNAP_M || !current.q.isWithinAngleDegrees(measured.q, SNAP_DEGREES)) {
        measured
      } else {
        Pose(current.t.lerp(measured.t, SMOOTHING), current.q.slerp(measured.q, SMOOTHING).normalize())
      }
  }

  /** An OpenXR [pose] in scene space, or null before the first [update]. */
  fun toScene(pose: Pose): Pose? = offset?.let { it * toLeftHanded(pose) }

  fun reset() {
    offset = null
  }

  private fun toLeftHanded(pose: Pose): Pose {
    // Reflect Z for positions and both sides of the rotation matrix (S * R * S). Reflecting
    // only the position would put the model in the right place but turn its grille the wrong way.
    val p = pose.t
    val q = pose.q
    return Pose(Vector3(p.x, p.y, -p.z), Quaternion(q.w, -q.x, -q.y, q.z))
  }

  private companion object {
    const val SNAP_M = 0.15f
    const val SNAP_DEGREES = 8f
    const val SMOOTHING = 0.1f
  }
}
