package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Touching a window in the dock brings that window to just above the dock, gliding there over
 * [durationMs] so the user sees where it came from. One recall runs at a time; the window keeps its
 * own Fixed/Follow mode afterwards (the caller re-anchors it each frame).
 */
internal class DockRecall(private val durationMs: Long = 280L) {
  private var screen: Int? = null
  private var from: Pose? = null
  private var to: Pose? = null
  private var startMs = 0L

  val active: Boolean get() = screen != null

  fun start(screen: Int, from: Pose, to: Pose, nowMs: Long) {
    this.screen = screen
    this.from = from
    this.to = to
    startMs = nowMs
  }

  /** The window and its pose for this frame while gliding (the last frame lands exactly on target). */
  fun tick(nowMs: Long): Pair<Int, Pose>? {
    val id = screen ?: return null
    val a = from ?: return null
    val b = to ?: return null
    val t = ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
    if (t >= 1f) {
      cancel()
      return id to b
    }
    val eased = t * t * (3f - 2f * t)
    return id to a.lerp(b, eased)
  }

  fun cancel() {
    screen = null
    from = null
    to = null
  }

  companion object {
    /** The window's bottom edge sits this far above the dock, as seen from the eyes. */
    const val CLEARANCE_DEG = 24f

    /**
     * A window of [heightM] at [distanceM] from [eye], in the dock's direction, with its bottom edge
     * [CLEARANCE_DEG] above the dock (room for the voice pane between them), facing the eyes.
     */
    fun targetPose(eye: Vector3, dock: Pose, heightM: Float, distanceM: Float): Pose {
      val toDock = dock.t - eye
      val horizontal = sqrt(toDock.x * toDock.x + toDock.z * toDock.z)
      val flat = if (horizontal > 0.001f) Vector3(toDock.x / horizontal, 0f, toDock.z / horizontal) else Vector3(0f, 0f, 1f)
      val bottomElevation = atan2(toDock.y, horizontal) + Math.toRadians(CLEARANCE_DEG.toDouble()).toFloat()
      val bottom = eye + flat * (distanceM * cos(bottomElevation)) + Vector3(0f, distanceM * sin(bottomElevation), 0f)
      val center = bottom + Vector3(0f, heightM / 2f, 0f)
      return Pose(center, Quaternion.lookRotation(center - eye))
    }
  }
}
