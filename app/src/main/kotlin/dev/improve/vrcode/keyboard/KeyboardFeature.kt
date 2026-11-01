package dev.improve.vrcode.keyboard

import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.dock.DockPlacement
import dev.improve.vrcode.voice.VoiceController
import dev.improve.xmux.client.Key
import dev.improve.xmux.client.Modifier
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.roundToInt

/**
 * A floating keyboard, opened from the pie menu or the dock. It appears just above the dock,
 * tilted toward the eyes, and can be grabbed and moved like any panel. Keys go to the voice pane
 * picked for editing if there is one (return ends editing), else to the focused terminal pane.
 */
class KeyboardFeature(private val xmux: XmuxSpatial, private val voice: VoiceController) {
  private val state = KeyboardState()
  private var entity: Entity? = null

  var shown = false
    private set

  fun onSceneReady() {
    val metresPerPx = xmux.config.windowWidthM / xmux.spec.widthPx
    val widthPx = (WIDTH_M / metresPerPx).roundToInt()
    val heightPx = (HEIGHT_M / metresPerPx).roundToInt()
    entity =
      xmux.mountPanel(widthPx, heightPx, Placement.InFrontOfHead) { TouchKeyboard(state, ::onKey, onClose = ::hide) }.also {
        it.setComponent(Visible(false))
      }
    Log.i(TAG, "keyboard ${widthPx}x$heightPx px (${(WIDTH_M * 100).roundToInt()} cm wide)")
  }

  fun toggle(head: Pose?) = if (shown) hide() else show(head)

  /**
   * Shows the keyboard just above the dock and a little closer than it, turned toward the eyes, so
   * the dock (always visible, even collapsed) stays uncovered while typing.
   */
  fun show(head: Pose?) {
    val panel = entity ?: return
    if (head != null) {
      val dock = DockPlacement.pose(head)
      val up = dock.q.times(Vector3(0f, 1f, 0f))
      val towardEyes = (head.t - dock.t).normalize()
      val position = dock.t + up * (DockPlacement.HEIGHT_M / 2f + GAP_ABOVE_DOCK_M + HEIGHT_M / 2f) + towardEyes * CLOSER_THAN_DOCK_M
      panel.setComponent(Transform(Pose(position, Quaternion.lookRotation(position - head.t))))
    }
    panel.setComponent(Visible(true))
    shown = true
    Log.i(TAG, "keyboard shown")
  }

  fun hide() {
    entity?.setComponent(Visible(false))
    shown = false
  }

  /** Keeps the header's target label current. */
  fun onSceneTick() {
    if (shown) state.target = target()
  }

  private fun target(): String =
    voice.editing?.let { "voice pane ${it.id}" } ?: if (xmux.focusedTerminal() != null) "focused pane" else "no pane focused"

  private fun onKey(action: KeyAction) {
    val note = voice.editing
    if (note != null) {
      when (action) {
        is KeyAction.Text -> voice.typeInto(note, action.text)
        KeyAction.Backspace -> voice.backspaceIn(note)
        KeyAction.Enter -> voice.stopEditing()
        is KeyAction.Named -> {}
      }
      return
    }
    val terminal = xmux.focusedTerminal() ?: return
    val ctrl = state.ctrl
    if (ctrl) state.ctrl = false
    when (action) {
      is KeyAction.Text ->
        if (ctrl && action.text.length == 1 && action.text[0].isLetter()) {
          terminal.keys(Key(action.text.lowercase(), setOf(Modifier.Ctrl)))
        } else {
          terminal.write(action.text)
        }
      KeyAction.Backspace -> terminal.keys(Key("backspace"))
      KeyAction.Enter -> terminal.keys(Key("enter"))
      is KeyAction.Named -> terminal.keys(Key(action.name, if (ctrl) setOf(Modifier.Ctrl) else emptySet()))
    }
  }

  private companion object {
    const val TAG = "vrcode"
    /** Two-handed touch typing: about 2.7 cm per letter key. */
    const val WIDTH_M = 0.30f
    const val HEIGHT_M = 0.16f
    const val GAP_ABOVE_DOCK_M = 0.02f
    const val CLOSER_THAN_DOCK_M = 0.06f
  }
}
