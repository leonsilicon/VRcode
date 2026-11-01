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
import kotlin.math.abs
import kotlin.math.max

/** A window's panel centre in pixels and its current metres per pixel, corner-handle scaling included. */
internal class PanelScale(val centre: Offset, val metresPerPixelX: Float, val metresPerPixelY: Float)

internal fun ScreenWindow.panelScale(): PanelScale {
  val spec = panelSize
  return PanelScale(Offset(spec.widthPx / 2f, spec.heightPx / 2f), widthM / spec.widthPx, heightM / spec.heightPx)
}

/**
 * Title-bar drags follow the hand that really holds them ([TitleBarDragGate]): a header drag event
 * moves its window only while a pinch or trigger is held that started on that title bar, and the
 * drag ends when it is released, whatever the header's gesture still reports.
 *
 * Push and pull while a window is dragged ([DragDepth] has the mapping): the header's drag events
 * carry only panel coordinates, and a hand pushed straight along its ray barely moves its hit point,
 * so depth is read every frame from that same hand. Main thread only.
 */
internal class TitleBarDepthDrag(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val move: (ScreenId, Pose) -> Unit,
) {
  private class Hand(val left: Boolean, val pose: Pose, val pressed: Boolean)

  private class Grab(val screen: ScreenId, val anchor: Offset, val left: Boolean, val depth: DragDepth) {
    var radius = 0f
  }

  private val gate = TitleBarDragGate { Log.i(TAG, it) }
  private var grab: Grab? = null

  /**
   * A header drag event on [screen]: its gesture grabbed the panel at [anchor] and points at
   * [position]. Returns the grab point to drag the window by, or null when no held press owns this
   * drag and the window must stay put. Starts following the holding hand's depth once per drag.
   */
  fun dragged(screen: ScreenId, anchor: Offset, position: Offset, head: Pose?): Offset? {
    val hands = hands()
    gate.update(handStates(hands))
    val grabbed = gate.drag(screen, anchor, position)
    val left = gate.holder(screen)
    if (grabbed == null || left == null) {
      if (grab?.screen == screen) end()
      return null
    }
    val current = grab
    if (current != null && current.screen == screen && current.anchor == grabbed) return grabbed
    grab = null
    if (head == null) return grabbed
    val window = xmux.windows.firstOrNull { it.screen == screen && it.visible } ?: return grabbed
    val pose = window.pose() ?: return grabbed
    val hand = hands.firstOrNull { it.left == left } ?: return grabbed
    val scale = window.panelScale()
    val radius = (titleBarGrabPoint(pose, grabbed, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY) - head.t).length()
    grab = Grab(screen, grabbed, left, DragDepth(radius, hand.pose.t - head.t)).also { it.radius = radius }
    Log.i(TAG, "depth drag: screen ${screen.value} held by the ${side(left)} hand at ${metres(radius)}")
    return grabbed
  }

  /** Tracks each hand's press and applies the holding hand's push or pull. [allowed] false (paused, Settings open) ends the depth drag. */
  fun tick(head: Pose?, allowed: Boolean) {
    val hands = hands()
    gate.update(handStates(hands))
    val current = grab ?: return
    val hand = hands.firstOrNull { it.left == current.left }
    val window = xmux.windows.firstOrNull { it.screen == current.screen && it.visible }
    val pose = window?.pose()
    if (!allowed || head == null || hand == null || !hand.pressed || gate.holder(current.screen) != current.left ||
      window == null || pose == null) {
      end()
      return
    }
    val scale = window.panelScale()
    val grabPoint = titleBarGrabPoint(pose, current.anchor, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY)
    val handFromHead = hand.pose.t - head.t
    val direction = hand.pose.forward()
    // Facing the head needs the grab point further from it than from the panel's centre (wide windows
    // grabbed near a corner), and the hand must stay inside the sphere so its ray can reach the grab point.
    val reach = (grabPoint - pose.t).length()
    val nearest = max(MIN_DISTANCE_M, max(reach + EDGE_CLEARANCE_M, handFromHead.length() + HAND_CLEARANCE_M))
    val radius = current.depth.radius(handFromHead, direction, nearest, MAX_DISTANCE_M)
    // Until the hand reaches, the header's own drag keeps the window on its sphere.
    if (abs(radius - (grabPoint - head.t).length()) < STILL_M) return
    val moved = titleBarDepthPose(current.anchor, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY,
      head.t, hand.pose.t, direction, radius) ?: return
    current.radius = radius
    move(current.screen, moved)
  }

  fun cancel() {
    end()
    gate.cancel()
  }

  private fun end() {
    val current = grab ?: return
    grab = null
    Log.i(TAG, "depth drag: screen ${current.screen.value} released at ${metres(current.radius)}")
  }

  /** Each hand's press, with the title bar its ray is on while pressed (the gate keeps the one it started on). */
  private fun handStates(hands: List<Hand>) = hands.map { hand ->
    val header = if (hand.pressed) xmux.hitTest(hand.pose.t, hand.pose.forward())?.takeIf { it.onHeader }?.window?.screen else null
    TitleBarDragGate.HandState(hand.left, hand.pressed, header)
  }

  /**
   * Each tracked hand or controller's pointer pose and whether it is pressing: its pinch or trigger,
   * or the A / X button that the SDK's panels also treat as a click.
   */
  private fun hands(): List<Hand> {
    val body = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()
      ?: return emptyList()
    return listOf(
      Triple(false, body.rightHand, ButtonBits.ButtonTriggerR or ButtonBits.ButtonA),
      Triple(true, body.leftHand, ButtonBits.ButtonTriggerL or ButtonBits.ButtonX),
    ).mapNotNull { (left, entity, buttons) ->
      val controller = entity.tryGetComponent<Controller>()?.takeIf { it.isActive } ?: return@mapNotNull null
      val pose = entity.tryGetComponent<Transform>()?.transform ?: return@mapNotNull null
      Hand(left, pose, controller.buttonState and buttons != 0)
    }
  }

  private companion object {
    const val TAG = "VrcodeWindow"
    /** Grab point distances: from close enough to read at arm's length to across a room. */
    const val MIN_DISTANCE_M = 0.45f
    const val MAX_DISTANCE_M = 4f
    const val HAND_CLEARANCE_M = 0.05f
    const val EDGE_CLEARANCE_M = 0.15f
    const val STILL_M = 0.001f

    fun side(left: Boolean) = if (left) "left" else "right"
    fun metres(value: Float) = String.format(Locale.US, "%.2f m", value)
  }
}
