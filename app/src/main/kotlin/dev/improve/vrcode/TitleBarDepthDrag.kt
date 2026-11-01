package dev.improve.vrcode

import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial
import java.util.Locale

/** A window's panel centre in pixels and its current metres per pixel, corner-handle scaling included. */
internal class PanelScale(val centre: Offset, val metresPerPixelX: Float, val metresPerPixelY: Float)

internal fun ScreenWindow.panelScale(): PanelScale {
  val spec = panelSize
  return PanelScale(Offset(spec.widthPx / 2f, spec.heightPx / 2f), widthM / spec.widthPx, heightM / spec.heightPx)
}

/**
 * Header gestures authorize a drag; only the holding hand's current ray moves it, once per scene
 * frame, through [TitleBarRayDrag]. Delayed header coordinates never move an already moving panel.
 * [TitleBarDragGate] ends ownership on physical release regardless of what Compose reports.
 * Main thread only. Only the right hand drags by the title bar; the left hand moves windows by
 * any part of them (windows/LeftHandMove.kt).
 */
internal class TitleBarDepthDrag(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val move: (ScreenId, Pose) -> Unit,
) {
  private class Hand(val left: Boolean, val pose: Pose, val pressed: Boolean)

  private class Grab(val screen: ScreenId, val left: Boolean, val motion: TitleBarRayDrag)

  private val gate = TitleBarDragGate { Log.i(TAG, it) }
  private val grabs = HashMap<ScreenId, Grab>()

  init {
    Log.i(TAG, "title-bar drag tracking: hand ray per scene frame")
  }

  /**
   * A header drag event on [screen]: its gesture grabbed the panel at [anchor] and points at
   * [position]. Starts tracking once per physical press; later events cannot reset its anchor or
   * apply motion. Returns true when a held press owns it, so the caller can focus the window.
   */
  fun dragged(screen: ScreenId, anchor: Offset, position: Offset, head: Pose?): Boolean {
    val hands = hands()
    gate.update(handStates(hands))
    val grabbed = gate.drag(screen, anchor, position)
    val left = gate.holder(screen)
    if (grabbed == null || left == null) {
      end(screen)
      return false
    }
    if (grabs[screen]?.left == left) return true
    if (head == null) return false
    val window = xmux.windows.firstOrNull { it.screen == screen && it.visible } ?: return false
    val pose = window.pose() ?: return false
    val hand = hands.firstOrNull { it.left == left } ?: return false
    val scale = window.panelScale()
    // The event's anchor can belong to an older compositor pose or a phantom gesture. Attach to
    // the current ray instead, so the first scene tick doesn't jump to those old coordinates.
    val point = titleBarRayPixel(pose, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY,
      hand.pose.t, hand.pose.forward()) ?: return false
    val motion = TitleBarRayDrag(pose, point, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY, head.t, hand.pose.t)
    grabs[screen] = Grab(screen, left, motion)
    Log.i(TAG, "ray drag: screen ${screen.value} held by the ${side(left)} hand at ${metres(motion.radius)}")
    return true
  }

  /** The only title-bar pose writer. [allowed] false (paused, modal UI open) cancels held drags. */
  fun tick(head: Pose?, allowed: Boolean) {
    val hands = hands()
    gate.update(handStates(hands))
    if (!allowed || head == null) {
      cancel()
      return
    }
    for (current in grabs.values.toList()) {
      val hand = hands.firstOrNull { it.left == current.left }
      val window = xmux.windows.firstOrNull { it.screen == current.screen && it.visible }
      if (hand == null || !hand.pressed || gate.holder(current.screen) != current.left || window?.pose() == null) {
        end(current.screen)
        continue
      }
      val moved = current.motion.move(head.t, hand.pose.t, hand.pose.forward()) ?: continue
      move(current.screen, moved)
    }
  }

  fun cancel() {
    for (screen in grabs.keys.toList()) end(screen)
    gate.cancel()
  }

  private fun end(screen: ScreenId) {
    val current = grabs.remove(screen) ?: return
    Log.i(TAG, "ray drag: screen ${current.screen.value} released at ${metres(current.motion.radius)}")
  }

  /** Each hand's press, with the title bar its ray is on while pressed (the gate keeps the one it started on). */
  private fun handStates(hands: List<Hand>) = hands.map { hand ->
    val header = if (hand.pressed) xmux.hitTest(hand.pose.t, hand.pose.forward())?.takeIf { it.onHeader }?.window?.screen else null
    TitleBarDragGate.HandState(hand.left, hand.pressed, header)
  }

  /**
   * The right hand or controller's pointer pose and whether it is pressing: its pinch or trigger,
   * or the A button that the SDK's panels also treat as a click. The left hand never presses inside
   * a window; it moves windows from anywhere on them instead (windows/LeftHandMove.kt).
   */
  private fun hands(): List<Hand> {
    val body = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()
      ?: return emptyList()
    return listOf(
      Triple(false, body.rightHand, ButtonBits.ButtonTriggerR or ButtonBits.ButtonA),
    ).mapNotNull { (left, entity, buttons) ->
      val controller = entity.tryGetComponent<Controller>()?.takeIf { it.isActive } ?: return@mapNotNull null
      val pose = entity.tryGetComponent<Transform>()?.transform ?: return@mapNotNull null
      Hand(left, pose, controller.buttonState and buttons != 0)
    }
  }

  private companion object {
    const val TAG = "VrcodeWindow"
    fun side(left: Boolean) = if (left) "left" else "right"
    fun metres(value: Float) = String.format(Locale.US, "%.2f m", value)
  }
}
