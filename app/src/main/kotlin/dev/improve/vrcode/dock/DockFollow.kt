package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.math.exp

/**
 * Lazy follow, so the dock's bar does not feel welded to the face. It stays put while the gaze
 * turns less than [yawDeadZoneDeg] sideways, nods less than [pitchDownDeadZoneDeg] down or
 * [pitchUpDeadZoneDeg] up, or the head moves less than [moveDeadZoneM], from where it last settled.
 * Looking up drops the bar further down the view, and the bar already sits near the bottom edge,
 * so that dead zone is small. Beyond these it eases toward [DockPlacement.pose] with an exponential
 * time constant of [timeConstantS] until it catches up, then rests again. It appears in place
 * without animating, and snaps after a recenter or teleport.
 */
internal class DockFollow(
  private val yawDeadZoneDeg: Float = 14f,
  private val pitchDownDeadZoneDeg: Float = 10f,
  private val pitchUpDeadZoneDeg: Float = 4f,
  private val moveDeadZoneM: Float = 0.10f,
  private val timeConstantS: Float = 0.45f,
) {
  private var eye: Vector3? = null
  private var yawDeg = 0f
  private var pitchDeg = 0f
  private var settling = false

  /** The dock's current pose, or null until the first update after [reset]. */
  var pose: Pose? = null
    private set

  fun update(head: Pose, dtS: Float): Pose {
    val targetYaw = DockPlacement.yawDegrees(head)
    val targetPitch = DockPlacement.pitchDegrees(head)
    val current = eye
    if (current == null || (head.t - current).length() > SNAP_M) {
      eye = head.t
      yawDeg = targetYaw
      pitchDeg = targetPitch
      settling = false
    } else {
      val yawError = wrap(targetYaw - yawDeg)
      val pitchError = targetPitch - pitchDeg
      val moveError = (head.t - current).length()
      val pitchOutside = pitchError > pitchUpDeadZoneDeg || -pitchError > pitchDownDeadZoneDeg
      if (abs(yawError) > yawDeadZoneDeg || pitchOutside || moveError > moveDeadZoneM) settling = true
      if (settling) {
        val step = 1f - exp(-dtS.coerceIn(0f, MAX_STEP_S) / timeConstantS)
        yawDeg = wrap(yawDeg + yawError * step)
        pitchDeg += pitchError * step
        eye = current + (head.t - current) * step
        val left = 1f - step
        if (abs(yawError) * left < SETTLED_DEG && abs(pitchError) * left < SETTLED_DEG && moveError * left < SETTLED_M) settling = false
      }
    }
    return DockPlacement.poseAt(eye ?: head.t, yawDeg, pitchDeg).also { pose = it }
  }

  fun reset() {
    eye = null
    settling = false
    pose = null
  }

  private fun wrap(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
  }

  private companion object {
    const val SNAP_M = 1.5f
    /** A stalled frame must not jump the dock across the view. */
    const val MAX_STEP_S = 0.1f
    const val SETTLED_DEG = 0.5f
    const val SETTLED_M = 0.005f
  }
}
