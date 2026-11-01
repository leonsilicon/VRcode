package dev.improve.vrcode.windows

import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.isdk.ResizeCornerState
import com.meta.spatial.runtime.PointerEvent
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import dev.improve.vrcode.TitleBarRayDrag
import dev.improve.vrcode.hands.HandAim
import dev.improve.vrcode.panelScale
import dev.improve.vrcode.titleBarRayPixel
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The left hand only moves terminal windows. Its index pinch (or the left controller's trigger or
 * X) grabs the window its ray is on, anywhere on it (title bar, terminal, header buttons) or just
 * past any edge or corner ([EdgeBand.around]), and drags it the way a title-bar drag does
 * ([TitleBarRayDrag]): the grabbed point stays on the ray at its distance from the head, the window
 * turns to face the user, and reaching out or drawing the hand back pushes or pulls it.
 *
 * It never does anything else to a window. Its presses never reach a window's content
 * (`SpatialConfig.windowClickButtons` leaves the left buttons out), edge resizing is the right
 * hand's ([EdgeResize]), and the corner handles of the window it points at are off unless the right
 * hand points at that window too. A panel in front of the window (the keyboard, the dock, a voice
 * pane) keeps the press. [EdgeGrip] rides out the pinch's dip and flicker. A grab-hand cursor
 * ([LeftHandCursor]) marks where the ray meets a window it would move, open while pointing and
 * closed while moving, so the left ray reads as the one that moves windows. Main thread only.
 */
@OptIn(SpatialSDKExperimentalAPI::class)
internal class LeftHandMove(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val predictedTimeNs: () -> Long,
  /** False for windows that cannot move now (fullscreen). */
  private val movable: (ScreenWindow) -> Boolean,
  private val move: (ScreenId, Pose) -> Unit,
  private val onStart: (ScreenWindow) -> Unit,
  /** The grab cursor's size, as Settings' pointer cursor size (1 is its usual size). */
  private val cursorSize: () -> Float = { 1f },
) : AutoCloseable {
  private class Hand(val origin: Vector3, val direction: Vector3, val pressed: Boolean)
  private class Grab(val window: ScreenWindow, val motion: TitleBarRayDrag)
  /** The nearest panel the SDK's own left ray reported since the last tick. */
  private class SdkHit(val entity: Entity, val distance: Float)

  private val aim = HandAim(left = true)
  private val press = EdgePress(left = true)
  private val grip = EdgeGrip<ScreenWindow>()
  private var grab: Grab? = null
  private var isdk: IsdkSystem? = null
  private var sdkHit: SdkHit? = null
  /** Windows whose corner handles this class turned off. */
  private val cornersOff = HashSet<ScreenWindow>()
  /** The open hand on a window the left pinch would move, the closed one on the window it moves. */
  private val cursor = LeftHandCursor(activity)

  private val observer: (PointerEvent) -> Unit = { event ->
    val hit = event.hitInfo
    val entity: Entity? = hit.entity
    if (entity != null && hit.distance != Float.MAX_VALUE && isLeft(event.source)) {
      val previous = sdkHit
      if (previous == null || hit.distance < previous.distance) sdkHit = SdkHit(entity, hit.distance)
    }
  }

  init {
    Log.i(TAG, "left-hand move: the left pinch moves windows from anywhere on them or just past their edges; it never clicks or resizes")
  }

  /** Starts, follows and ends left-hand moves. [allowed] false ends one; [blocked] rays (the dock) never grab. */
  fun tick(head: Pose?, allowed: Boolean, blocked: (Vector3, Vector3) -> Boolean) {
    if (isdk == null) isdk = activity.systemManager.tryFindSystem<IsdkSystem>()?.also { it.registerObserver(observer) }
    val inFront = sdkHit
    sdkHit = null
    val now = SystemClock.uptimeMillis()
    val hand = if (allowed && head != null) hand() else null
    grab?.let { current ->
      val reason = when {
        !allowed || head == null -> "input paused"
        current.window !in xmux.windows || !canMove(current.window) -> "window gone"
        !grip.hold(now, hand?.pressed) -> if (hand == null) "hand lost" else "released"
        else -> null
      }
      if (reason != null) end(reason)
      else if (hand != null && hand.pressed) follow(current, hand, head!!)
    }
    var pointed = grab?.window
    var hover: ScreenWindow? = null
    if (grab == null) {
      if (hand == null) {
        grip.lost()
        press.reset()
      } else {
        val target = target(hand, inFront, blocked)
        pointed = target
        hover = target
        val grabbed = grip.press(now, hand.pressed, target) { near(it, hand, blocked) }
        if (grabbed != null && head != null) start(grabbed, hand, head)
      }
    }
    corners(off = pointed?.takeIf { it != rightWindow() })
    showCursor(hover, hand)
  }

  /** The closed hand at the grabbed point while moving a window, else the open hand where [hand] points at [hover]. */
  private fun showCursor(hover: ScreenWindow?, hand: Hand?) {
    val size = cursorSize()
    grab?.let { return cursor.show(it.window, it.motion.anchor, grabbing = true, size) }
    val window = hover ?: return cursor.hide()
    val pose = window.pose() ?: return cursor.hide()
    val scale = window.panelScale()
    val pixel = hand?.let { titleBarRayPixel(pose, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY, it.origin, it.direction) }
      ?: return cursor.hide()
    cursor.show(window, pixel, grabbing = false, size)
  }

  /** Ends any move and turns every corner handle back on. */
  fun cancel() {
    end("cancelled")
    grip.lost()
    press.reset()
    corners(off = null, force = true)
    cursor.hide()
  }

  override fun close() {
    cancel()
    isdk?.unregisterObserver(observer)
    isdk = null
    aim.close()
    cursor.close()
  }

  private fun canMove(window: ScreenWindow) = window.visible && window.screen != null && movable(window)

  /**
   * The window [hand] points at: the nearest one whose body or surrounding band its ray crosses,
   * unless [blocked] or another panel the SDK's ray hit ([inFront]) lies in front of it.
   */
  private fun target(hand: Hand, inFront: SdkHit?, blocked: (Vector3, Vector3) -> Boolean): ScreenWindow? {
    if (blocked(hand.origin, hand.direction)) return null
    var best: ScreenWindow? = null
    var bestDistance = Float.MAX_VALUE
    xmux.hitTest(hand.origin, hand.direction)?.let { hit ->
      val point = hit.point ?: return@let
      if (canMove(hit.window)) {
        best = hit.window
        bestDistance = (point - hand.origin).length()
      }
    }
    for (window in xmux.windows) {
      if (!canMove(window)) continue
      val pose = window.pose() ?: continue
      val distance = EdgeBand.around(hand.origin, hand.direction, pose, window.widthM, window.heightM) ?: continue
      if (distance < bestDistance) {
        best = window
        bestDistance = distance
      }
    }
    val found = best ?: return null
    val front = inFront?.takeIf { hit -> xmux.windows.none { it.entity == hit.entity } }
    return found.takeUnless { front != null && front.distance < bestDistance - IN_FRONT_M }
  }

  /** True while [hand] still points at, or close past the edge of, the [window] it just left. */
  private fun near(window: ScreenWindow, hand: Hand, blocked: (Vector3, Vector3) -> Boolean): Boolean {
    if (window !in xmux.windows || !canMove(window) || blocked(hand.origin, hand.direction)) return false
    if (xmux.hitTest(hand.origin, hand.direction)?.window == window) return true
    val pose = window.pose() ?: return false
    val distance = EdgeBand.planePoint(hand.origin, hand.direction, pose)?.distance ?: return false
    return EdgeBand.around(hand.origin, hand.direction, pose, window.widthM, window.heightM,
      bandM = EdgeBand.bandM(distance) * 2f) != null
  }

  private fun start(window: ScreenWindow, hand: Hand, head: Pose) {
    val pose = window.pose() ?: return
    val scale = window.panelScale()
    // Grabbed where the ray crosses the window's plane now, so a press that latched a window the
    // ray had just left does not jump; outside the window is fine, the drag is plane geometry.
    val point = titleBarRayPixel(pose, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY,
      hand.origin, hand.direction) ?: return
    val motion = TitleBarRayDrag(pose, point, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY, head.t, hand.origin)
    grab = Grab(window, motion)
    onStart(window)
    Log.i(TAG, "left-hand move: screen ${window.screen?.value} grabbed at (${point.x.roundToInt()}, ${point.y.roundToInt()}) px " +
      "of ${window.panelSize.widthPx} x ${window.panelSize.heightPx}, ${metres(motion.radius)} from the head")
  }

  private fun follow(current: Grab, hand: Hand, head: Pose) {
    val screen = current.window.screen ?: return
    val moved = current.motion.move(head.t, hand.origin, hand.direction) ?: return
    move(screen, moved)
  }

  private fun end(reason: String) {
    val current = grab ?: return
    grab = null
    Log.i(TAG, "left-hand move: screen ${current.window.screen?.value} $reason at ${metres(current.motion.radius)}")
  }

  /**
   * Turns off the corner handles of [off] (the window the left hand points at or holds) and back on
   * everywhere else. A resize already under way keeps its handles until it ends, unless [force].
   */
  private fun corners(off: ScreenWindow?, force: Boolean = false) {
    for (window in xmux.windows) {
      val enabled = window != off
      if (enabled && window !in cornersOff) continue
      val entity = window.entity ?: continue
      val resize = entity.tryGetComponent<IsdkPanelResize>() ?: continue
      if (resize.enabled != enabled) {
        if (!force && resize.activeResizeCorner != ResizeCornerState.NONE) continue
        resize.enabled = enabled
        entity.setComponent(resize)
      }
      if (enabled) cornersOff -= window else cornersOff += window
    }
    cornersOff.retainAll(xmux.windows.toSet())
  }

  /** The window the right hand points at, or close past an edge of: it keeps that window's corner handles on. */
  private fun rightWindow(): ScreenWindow? {
    val body = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody() ?: return null
    val entity = body.rightHand
    if (entity.tryGetComponent<Controller>()?.isActive != true) return null
    val pose = entity.tryGetComponent<Transform>()?.transform ?: return null
    xmux.hitTest(pose.t, pose.forward())?.let { return it.window }
    return xmux.windows.firstOrNull { window ->
      val at = window.pose() ?: return@firstOrNull false
      window.visible && EdgeBand.around(pose.t, pose.forward(), at, window.widthM, window.heightM) != null
    }
  }

  /** The left hand or controller's ray and press: the runtime's own index pinch for a tracked hand. */
  private fun hand(): Hand? {
    val body = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody() ?: return null
    val entity = body.leftHand
    val controller = entity.tryGetComponent<Controller>()?.takeIf { it.isActive } ?: return null
    val pose = entity.tryGetComponent<Transform>()?.transform ?: return null
    val isHand = controller.type == ControllerType.HAND
    val frame = if (isHand) aim.poll(activity.scene, predictedTimeNs()) else null
    val pressed = press.update(isHand, controller.buttonState, frame) ?: return null
    return Hand(pose.t, pose.forward(), pressed)
  }

  private companion object {
    const val TAG = "VrcodeWindow"
    /** A panel the SDK's ray hit at least this much nearer than the window is in front of it. */
    const val IN_FRONT_M = 0.03f

    fun isLeft(source: Entity): Boolean =
      source.tryGetComponent<AvatarAttachment>()?.type.let { it == "left_hand" || it == "left_controller" }

    fun metres(value: Float) = String.format(Locale.US, "%.2f m", value)
  }
}
