package dev.improve.vrcode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Pose

/** One mounted window's mode and its pose relative to the full (yaw/pitch/roll) head pose. */
internal class WindowMotion(initialMode: WindowMode) {
  var mode by mutableStateOf(initialMode)
    private set
  private var headRelativePose: Pose? = null

  fun setMode(value: WindowMode, head: Pose?, world: Pose?) {
    mode = value
    headRelativePose = if (value == WindowMode.Follow && head != null && world != null) head.inverse() * world else null
  }

  /** Fixed windows require no transform writes; following starts at the first tracked pose. */
  fun poseForFrame(head: Pose, world: Pose): Pose? {
    if (mode == WindowMode.Fixed) return null
    val relative = headRelativePose ?: (head.inverse() * world).also { headRelativePose = it }
    return head * relative
  }

  /** Rebase after an explicit move, so dragging/arranging is retained on the next frame. */
  fun moved(head: Pose?, world: Pose) {
    headRelativePose = if (mode == WindowMode.Follow && head != null) head.inverse() * world else null
  }
}
