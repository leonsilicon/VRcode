package dev.improve.vrcode.dock

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.DockPanel
import dev.improve.vrcode.DockPill
import dev.improve.vrcode.DockWindow
import dev.improve.xmux.spatial.WindowLayout
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.roundToInt

/** A pointer ray in world space. */
internal data class DockRay(val origin: Vector3, val direction: Vector3)

/**
 * Pooled panels, independent of terminal reconnects and terminal window follow/fixed modes. The
 * collapsed handle is a separate small panel, so the full dock's quad never sits invisibly in
 * front of terminals and swallows their pointer input.
 */
internal class DockFeature(
  private val xmux: XmuxSpatial,
  private val onWindow: (Int) -> Unit,
  private val onKeyboard: () -> Unit,
  private val onArrange: () -> Unit,
  private val onSettings: () -> Unit,
  private val onConnection: () -> Unit,
) {
  private var panel: Entity? = null
  private var pill: Entity? = null
  private val follow = DockFollow()
  private var lastTickMs: Long? = null
  private val reveal = DockReveal()
  private var windows by mutableStateOf(emptyList<DockWindow>())
  private var focused by mutableStateOf<Int?>(null)
  private var online by mutableStateOf(false)
  private var keyboard by mutableStateOf(false)

  val expanded: Boolean get() = reveal.expanded

  /** Where the dock is drawn right now (it eases toward the head), or null while hidden. */
  fun currentPose(): Pose? = follow.pose

  fun onSceneReady() {
    if (panel != null) return
    val metresPerPx = xmux.config.windowWidthM / xmux.spec.widthPx
    panel = mount(DockPlacement.WIDTH_M, DockPlacement.HEIGHT_M, metresPerPx) {
      DockPanel(windows, focused, online, keyboard, onWindow, onKeyboard, onArrange, onSettings, onConnection)
    }
    pill = mount(DockPlacement.PILL_WIDTH_M, DockPlacement.PILL_HEIGHT_M, metresPerPx) {
      DockPill(online) { reveal.reveal(SystemClock.uptimeMillis()) }
    }
  }

  private fun mount(widthM: Float, heightM: Float, metresPerPx: Float, content: @Composable () -> Unit): Entity =
    xmux.mountPanel((widthM / metresPerPx).roundToInt(), (heightM / metresPerPx).roundToInt()) { content() }.also {
      // The pose is owned by the headset; grabbing must not fight the follow loop. Never remove
      // IsdkGrabbable from a panel: the SDK's panel handle system still reads it and aborts the
      // app ("Failed to get component IsdkGrabbable ... wrong data type"). Disable it instead.
      it.setComponent(IsdkGrabbable(enabled = false))
      it.setComponent(Visible(false))
    }

  /**
   * Shown whenever the head is tracked and [visible], easing toward the head (see [DockFollow]) and
   * appearing in place after being hidden. Opens while any [rays] cross the visible
   * footprint or any [hands] come within reach of it, and collapses shortly after they leave.
   */
  fun onSceneTick(
    head: Pose?, visible: Boolean, connected: Boolean, keyboardShown: Boolean,
    rays: List<DockRay> = emptyList(), hands: List<Vector3> = emptyList(), nowMs: Long = SystemClock.uptimeMillis(),
  ) {
    online = connected
    keyboard = keyboardShown
    windows = xmux.windows.mapNotNull { w -> w.screen?.let { DockWindow(it.value, w.state.title) } }
    focused = (xmux.focused ?: xmux.windows.firstOrNull())?.screen?.value
    val tracked = head?.takeIf(WindowLayout::isUsableHeadPose)
    if (!visible || tracked == null) {
      hide()
      return
    }
    val dtS = lastTickMs?.let { (nowMs - it) / 1000f } ?: 0f
    lastTickMs = nowMs
    val dock = follow.update(tracked, dtS)
    val (width, height) = footprint()
    val wasOpen = reveal.expanded
    // Hands rest near chest height, right by the handle, so opening it takes a deliberate reach;
    // once open, a hand moving between buttons keeps it open. Typing on the floating keyboard puts
    // both hands next to the dock, so only pointers count then.
    val reach = if (wasOpen) HOLD_REACH_M else OPEN_REACH_M
    // Hands typing on a physical keyboard sit just below the dock and their rays graze it. Pointing
    // at something within arm's reach from a few centimetres is not deliberate, so near rays only
    // count as a reach.
    val pointed = rays.any { (DockPlacement.hitDistance(dock, it.origin, it.direction, width, height) ?: 0f) >= MIN_POINT_M }
    val touched = !keyboardShown && hands.any { DockPlacement.distanceTo(dock, it, width, height) <= reach }
    val open = reveal.update(pointed || touched, nowMs)
    if (open != wasOpen) Log.d(TAG, if (open) "dock opened (pointer=$pointed, hand=$touched)" else "dock collapsed")
    val pose = Transform(dock)
    panel?.setComponent(pose)
    pill?.setComponent(pose)
    panel?.setComponent(Visible(open))
    pill?.setComponent(Visible(!open))
  }

  private fun footprint(): Pair<Float, Float> =
    if (reveal.expanded) DockPlacement.WIDTH_M to DockPlacement.HEIGHT_M else DockPlacement.PILL_WIDTH_M to DockPlacement.PILL_HEIGHT_M

  /** Only blocks a terminal if the visible dock is in front of it, preserving nearer panels. */
  fun blocksRay(origin: Vector3, direction: Vector3): Boolean {
    val distance = hitDistance(origin, direction) ?: return false
    val terminal = xmux.hitTest(origin, direction)?.point?.distanceTo(origin)
    return terminal == null || distance < terminal
  }

  /** Distance along a ray to whichever dock footprint is currently visible. */
  fun hitDistance(origin: Vector3, direction: Vector3): Float? {
    val dock = follow.pose ?: return null
    val (width, height) = footprint()
    return DockPlacement.hitDistance(dock, origin, direction, width, height)
  }

  fun hide() {
    follow.reset()
    lastTickMs = null
    reveal.collapse()
    panel?.setComponent(Visible(false))
    pill?.setComponent(Visible(false))
  }

  fun close() {
    hide()
    panel?.destroy()
    pill?.destroy()
    panel = null
    pill = null
  }

  private companion object {
    const val TAG = "VrcodeDock"
    /** Distances from the hand's tracked pose, which sits a few centimetres behind the fingertips. */
    const val OPEN_REACH_M = 0.06f
    const val HOLD_REACH_M = 0.10f
    const val MIN_POINT_M = 0.25f
  }
}
