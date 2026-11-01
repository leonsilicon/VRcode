package dev.improve.vrcode

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial

/**
 * Keeps one terminal window focused whenever any is visible, like the frontmost window on a Mac:
 * voice and keyboard input always have somewhere to go without the user picking a pane first.
 * When the focused window closes or is minimized, focus returns to the most recently focused
 * visible window, else the one nearest the line of sight. Call [tick] once per frame.
 */
class FocusKeeper(private val xmux: XmuxSpatial) {
  /** The focused window's screen, as Compose state so headers can mark it. */
  var focusedScreen by mutableStateOf<ScreenId?>(null)
    private set

  /** Most recently focused first. */
  private val recent = ArrayDeque<ScreenId>()

  val focused: ScreenWindow? get() = xmux.focused?.takeIf { it.session != null }

  fun tick(head: Pose?) {
    var window = focused
    if (window == null) {
      val candidates = xmux.windows.filter { it.visible && it.session != null && it.screen != null }
      val choice =
        FocusChoice.pick(recent, candidates.map { FocusChoice.Candidate(it.screen!!, it.pose()?.t) }, head?.t, head?.forward())
      window = candidates.firstOrNull { it.screen == choice }
      if (window != null) {
        xmux.focus(window)
        Log.i(TAG, "focus: screen ${window.screen?.value} (\"${window.state.title}\") kept focused")
      }
    }
    val screen = window?.screen
    if (screen != null && recent.firstOrNull() != screen) {
      recent.remove(screen)
      recent.addFirst(screen)
      while (recent.size > MAX_RECENT) recent.removeLast()
    }
    if (focusedScreen != screen) focusedScreen = screen
  }

  private companion object {
    const val TAG = "vrcode"
    const val MAX_RECENT = 16
  }
}

/** Which window takes focus when none has it. Pure, for tests. */
object FocusChoice {
  class Candidate(val screen: ScreenId, val position: Vector3?)

  /**
   * The most recently focused of [candidates] ([recent] is most recent first), else the one whose
   * centre is nearest the line of sight from [eye] along [forward], else the first.
   */
  fun pick(recent: Collection<ScreenId>, candidates: List<Candidate>, eye: Vector3?, forward: Vector3?): ScreenId? {
    if (candidates.isEmpty()) return null
    recent.firstOrNull { screen -> candidates.any { it.screen == screen } }?.let { return it }
    if (eye != null && forward != null && forward.length() > 1e-5f) {
      val gaze = forward.normalize()
      candidates
        .filter { it.position != null && (it.position - eye).length() > 1e-5f }
        .maxByOrNull { (it.position!! - eye).normalize().dot(gaze) }
        ?.let { return it.screen }
    }
    return candidates.first().screen
  }
}
