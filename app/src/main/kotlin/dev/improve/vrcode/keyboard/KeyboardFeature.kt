package dev.improve.vrcode.keyboard

import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
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
 * The keyboard opened from the pie menu, quick inputs or the dock.
 *
 * It is Meta's own system keyboard when possible: the focused terminal window's view is an Android
 * text editor (xmux's `ScreenView` takes typed text, backspace and key events through its input
 * connection), so focusing it and asking Android to show the soft input brings up the Quest's
 * keyboard, typing straight into the focused pane.
 *
 * Otherwise, with no terminal window, while a voice pane is being edited, or if Android declines,
 * it is VRcode's floating keyboard. That appears just above the dock, tilted toward the eyes, and
 * can be grabbed and moved like any panel. Its keys go to the voice pane picked for editing if there
 * is one (return ends editing), else to the focused terminal pane.
 */
class KeyboardFeature(private val xmux: XmuxSpatial, private val voice: VoiceController) {
  private val state = KeyboardState()
  private var entity: Entity? = null
  private var floating = false

  /** The terminal view Meta's keyboard was opened for, while it is up. */
  private var systemView: View? = null
  private var systemSeen = false

  val shown: Boolean get() = floating || systemView != null

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
    if (voice.editing == null && showSystem()) return
    showFloating(head)
  }

  /** Meta's keyboard for the focused terminal window; false if there is none or Android declines. */
  private fun showSystem(): Boolean {
    val window = xmux.focused?.takeIf { it.visible } ?: xmux.windows.firstOrNull { it.visible }
    val view = window?.screenView ?: return false
    val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return false
    val focused = view.requestFocus()
    val accepted = imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    Log.i(TAG, "system keyboard for screen ${window.screen?.value}: focused=$focused accepted=$accepted")
    if (!accepted) return false
    systemView = view
    systemSeen = false
    return true
  }

  private fun showFloating(head: Pose?) {
    val panel = entity ?: return
    if (head != null) {
      val dock = DockPlacement.pose(head)
      val up = dock.q.times(Vector3(0f, 1f, 0f))
      val towardEyes = (head.t - dock.t).normalize()
      val position = dock.t + up * (DockPlacement.HEIGHT_M / 2f + GAP_ABOVE_DOCK_M + HEIGHT_M / 2f) + towardEyes * CLOSER_THAN_DOCK_M
      panel.setComponent(Transform(Pose(position, Quaternion.lookRotation(position - head.t))))
    }
    panel.setComponent(Visible(true))
    floating = true
    Log.i(TAG, "keyboard shown")
  }

  fun hide() {
    systemView?.let { view ->
      view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
      Log.i(TAG, "system keyboard hidden")
    }
    systemView = null
    entity?.setComponent(Visible(false))
    floating = false
  }

  /**
   * Keeps the floating keyboard's target label current, and notices Meta's keyboard being closed
   * with its own button: once the window has reported the keyboard showing, its going away counts.
   */
  fun onSceneTick() {
    if (floating) state.target = target()
    val view = systemView ?: return
    val up = view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: return
    if (up) {
      systemSeen = true
    } else if (systemSeen) {
      systemView = null
      Log.i(TAG, "system keyboard closed")
    }
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
