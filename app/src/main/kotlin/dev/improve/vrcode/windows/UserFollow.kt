package dev.improve.vrcode.windows

import com.meta.spatial.core.Vector3

/**
 * The point Follow windows hang from: the user's head position, smoothed so the windows can be read
 * while walking.
 *
 * It rests until the head moves more than [deadZoneM] from it (leaning in to read, swaying, or the
 * few centimetres the eyes shift when the head turns leave the windows still), then follows the
 * head as a critically damped spring until it catches up, and rests again. A spring starts and
 * stops with no jump in speed, where an exponential ease lurches to full speed the moment the dead
 * zone is crossed. Height follows more slowly than the floor plane ([verticalTimeS]): the head bobs
 * a few centimetres with every step (about 2 Hz), and text that bobs a little behind the head is
 * hard to read on the move, but only standing up or sitting down should move the windows up or
 * down. Across the floor, about half of a gait's 1 Hz sideways sway still reaches the windows, as
 * it did with the ease; filtering more would make them trail further. A jump of more than [snapM]
 * (tracking coming back elsewhere) places it at once.
 *
 * [horizontalTimeS] is the spring's smoothing time: walking steadily, the anchor trails the head
 * by the walking speed times this (0.49 m at 1.4 m/s for 0.35 s).
 */
internal class UserFollow(
  private val deadZoneM: Float = 0.4f,
  private val horizontalTimeS: Float = 0.35f,
  private val verticalTimeS: Float = 0.6f,
  private val snapM: Float = 3f,
) {
  var position: Vector3? = null
    private set

  private var vx = 0f
  private var vy = 0f
  private var vz = 0f
  private var settling = false

  /** The anchor for a frame [dtS] after the previous one, with the head at [target]. */
  fun update(target: Vector3, dtS: Float): Vector3 {
    val current = position
    if (current == null || (target - current).length() > snapM) return place(target)
    if ((target - current).length() > deadZoneM) settling = true
    if (!settling) return current
    val dt = dtS.coerceIn(0f, MAX_STEP_S)
    if (dt == 0f) return current
    val x = spring(current.x, target.x, vx, horizontalTimeS, dt)
    vx = velocity
    val y = spring(current.y, target.y, vy, verticalTimeS, dt)
    vy = velocity
    val z = spring(current.z, target.z, vz, horizontalTimeS, dt)
    vz = velocity
    val next = Vector3(x, y, z)
    val speed = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
    if ((target - next).length() < SETTLED_M && speed < SETTLED_SPEED_M_S) {
      settling = false
      vx = 0f
      vy = 0f
      vz = 0f
    }
    return next.also { position = it }
  }

  fun reset() {
    position = null
    settling = false
    vx = 0f
    vy = 0f
    vz = 0f
  }

  private fun place(target: Vector3): Vector3 {
    reset()
    return target.also { position = it }
  }

  /** The velocity [spring] leaves for its axis. */
  private var velocity = 0f

  /**
   * One step of a critically damped spring from [from] (moving at [speed]) toward [to], with
   * smoothing time [timeS]: the closed-form step game engines use (Unity's `SmoothDamp`), stable
   * and frame-rate independent for any step up to [MAX_STEP_S].
   */
  private fun spring(from: Float, to: Float, speed: Float, timeS: Float, dt: Float): Float {
    val omega = 2f / timeS
    val x = omega * dt
    val decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
    val change = from - to
    val temp = (speed + omega * change) * dt
    velocity = (speed - omega * temp) * decay
    return to + (change + temp) * decay
  }

  private companion object {
    /** A stalled frame must not throw the windows across the view. */
    const val MAX_STEP_S = 0.1f
    const val SETTLED_M = 0.003f
    const val SETTLED_SPEED_M_S = 0.01f
  }
}
