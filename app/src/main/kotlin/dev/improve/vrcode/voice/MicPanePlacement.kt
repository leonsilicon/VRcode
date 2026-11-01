package dev.improve.vrcode.voice

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.exp

/**
 * Where the live voice pane floats: just above the mic in the fist that holds it, upright and
 * turned toward the eyes, so the free hand can press its buttons without reaching for the dock.
 * On a dictation, its corner nearest the free hand carries the text mic's Enter-after toggle.
 */
internal object MicPanePlacement {
  /** How far the mic's top reaches along the grip's +Y (the props' speech ends sit 14-21 cm up). */
  const val MIC_TOP_M = 0.21f

  /** Clearance between the mic's top and the pane's bottom edge. */
  const val GAP_M = 0.03f

  /** The pane's centre stays at least this far below the eyes, so a mic raised to the mouth never puts it in the face. */
  const val BELOW_EYES_M = 0.12f

  /** ...and at least this far in front of them (horizontally), so it stays readable and pokeable. */
  const val MIN_REACH_M = 0.30f

  /** A pane left behind (staged after the fist opened) stays put while it is within this angle of the gaze. */
  private const val IN_VIEW_DEG = 55f
  private const val IN_VIEW_MAX_M = 1.0f

  /** The pane's centre above the mic held at [grip], for eyes at [eye]. */
  fun target(grip: Pose, eye: Vector3, paneHeightM: Float): Vector3 {
    val top = grip.t + grip.q.times(Vector3(0f, 1f, 0f)) * MIC_TOP_M
    val bottom = maxOf(grip.t.y, top.y) + GAP_M
    val y = minOf(bottom + paneHeightM / 2f, eye.y - BELOW_EYES_M)
    val flat = Vector3(grip.t.x - eye.x, 0f, grip.t.z - eye.z)
    val reach = flat.length()
    if (reach >= MIN_REACH_M) return Vector3(grip.t.x, y, grip.t.z)
    // A fist pulled in close: push the pane out along the same bearing (straight ahead if none).
    val bearing = if (reach > 0.01f) flat * (1f / reach) else Vector3(0f, 0f, 1f)
    return Vector3(eye.x + bearing.x * MIN_REACH_M, y, eye.z + bearing.z * MIN_REACH_M)
  }

  /**
   * Whether the current pane shows. While its fist is [holding] the mic, only once the recognizer
   * has transcribed a word into it ([text], finalized or still a guess), so a fist alone shows just
   * the mic; a pane being added to already has words. A [notice] (the mic could not listen, or the
   * text could not be typed) shows at once and after the fist opens, so a failure is never silent.
   */
  fun shown(holding: Boolean, text: String, notice: String?): Boolean = notice != null || (holding && text.isNotBlank())

  /** The pane at [centre], facing eyes at [eye]. */
  fun pose(centre: Vector3, eye: Vector3): Pose = Pose(centre, Quaternion.lookRotation(centre - eye))

  /** Whether a pane at [centre] is still in front of [head], near enough to read and press. */
  fun inView(centre: Vector3, head: Pose): Boolean {
    val away = centre - head.t
    val distance = away.length()
    if (distance > IN_VIEW_MAX_M || distance < 0.05f) return false
    val cos = away.dot(head.forward().normalize()) / distance
    return cos >= kotlin.math.cos(Math.toRadians(IN_VIEW_DEG.toDouble())).toFloat()
  }
}

/**
 * Eases the pane toward its target so it neither jitters with hand tracking nor drifts away from
 * a fingertip mid-press: it rests until the target moves more than [deadZoneM], then follows with
 * an exponential time constant of [timeConstantS] until it catches up. The first update (and a
 * jump of more than [snapM], such as tracking coming back elsewhere) places it at once. Terminal
 * windows in Follow mode use it, with a wider dead zone, to follow the user's position.
 */
internal class PaneFollow(
  private val deadZoneM: Float = 0.02f,
  private val timeConstantS: Float = 0.12f,
  private val snapM: Float = 0.6f,
) {
  var position: Vector3? = null
    private set

  private var settling = false

  fun update(target: Vector3, dtS: Float): Vector3 {
    val current = position
    if (current == null || (target - current).length() > snapM) {
      settling = false
      return target.also { position = it }
    }
    val error = (target - current).length()
    if (error > deadZoneM) settling = true
    if (!settling) return current
    val step = 1f - exp(-dtS.coerceIn(0f, MAX_STEP_S) / timeConstantS)
    if (error * (1f - step) < SETTLED_M) settling = false
    return (current + (target - current) * step).also { position = it }
  }

  fun reset() {
    position = null
    settling = false
  }

  private companion object {
    /** A stalled frame must not throw the pane across the view. */
    const val MAX_STEP_S = 0.1f
    const val SETTLED_M = 0.003f
  }
}
