package dev.improve.vrcode.hands

import android.util.Log
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import dev.improve.xmux.client.MouseAction
import dev.improve.xmux.client.MouseEvent
import dev.improve.xmux.spatial.XmuxSpatial
import dev.improve.xmux.spatial.WindowHit

/** Right-hand middle pinch opens the Quest context menu, or sends a right-click when embedded without one. */
class RightClickFeature(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val predictedTimeNs: () -> Long,
  private val onContextMenu: ((WindowHit) -> Unit)? = null,
) : AutoCloseable {
  private val pinch = RightMiddlePinch()
  private val gate = MiddlePinchGate()

  fun onSceneTick(enabled: Boolean) {
    if (!enabled) {
      cancel()
      return
    }
    val right = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()
      ?.tryGetLocalPlayerAvatarBody()?.rightHand
    val controller = right?.tryGetComponent<Controller>()
    val ray = if (controller?.isActive == true && controller.type == ControllerType.HAND)
      right?.tryGetComponent<Transform>()?.transform else null
    val signal = if (ray != null) pinch.poll(activity.scene, predictedTimeNs()) else null
    if (!gate.update(signal) || ray == null) return

    val hit = xmux.hitTest(ray.t, ray.forward()) ?: return
    if (hit.onHeader || hit.row < 0 || hit.col < 0) return
    if (onContextMenu != null) {
      xmux.focus(hit.window, hit.pane)
      onContextMenu.invoke(hit)
      return
    }
    val terminalId = hit.terminal ?: return
    val session = hit.window.session ?: return
    xmux.focus(hit.window, hit.pane)
    val terminal = session.terminal(terminalId)
    // Complete the click immediately so tracking loss or disconnect cannot leave a button held.
    terminal.mouse(MouseEvent(MouseAction.Press, RIGHT_BUTTON, hit.row, hit.col))
    terminal.mouse(MouseEvent(MouseAction.Release, RIGHT_BUTTON, hit.row, hit.col))
    Log.d("vrcode", "right middle pinch: right-click terminal=${terminalId.value} row=${hit.row} col=${hit.col}")
  }

  fun cancel() = gate.cancel()

  override fun close() {
    cancel()
    pinch.close()
  }

  private companion object {
    const val RIGHT_BUTTON = 2 // xmux: 0 left, 1 middle, 2 right
  }
}
