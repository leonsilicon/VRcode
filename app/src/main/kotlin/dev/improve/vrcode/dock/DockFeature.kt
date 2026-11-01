package dev.improve.vrcode.dock

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.DockBar
import dev.improve.vrcode.DockBody
import dev.improve.vrcode.DockOption
import dev.improve.vrcode.DockOptionKind
import dev.improve.vrcode.hands.HandAim
import dev.improve.xmux.spatial.WindowLayout
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.roundToInt

/**
 * The dock, used like an iPhone's home bar (see [DockGesture]): a short faint bar at the bottom of
 * the view that bulges under a hand and is swiped up to raise the dock; options are pressed, slid
 * along and flicked up to activate; the bar at the raised dock's top centre is swiped down to lower
 * it.
 *
 * Two pooled panels, independent of terminal reconnects: the bar (small and hittable, so pointers
 * never click a terminal through it) and the raised body (visual only, never hittable, so it can
 * never swallow terminal input; [hitDistance] treats it as foreground geometrically instead). The
 * bar is always rendered while the head is tracked and [onSceneTick]'s `visible` is set, including
 * while settings is open: it is how the user can tell VRcode loaded.
 */
internal class DockFeature(
  private val xmux: XmuxSpatial,
  private val scene: () -> Scene,
  private val predictedTimeNs: () -> Long,
  private val onWindow: (Int) -> Unit,
  private val onKeyboard: () -> Unit,
  private val onArrange: () -> Unit,
  private val onSettings: () -> Unit,
  private val onConnection: () -> Unit,
) {
  private var barPanel: Entity? = null
  private var bodyPanel: Entity? = null
  private val follow = DockFollow()
  private val gesture = DockGesture()
  private val hands = listOf(HandAim(left = true), HandAim(left = false))
  private var lastTickMs: Long? = null
  private var reveals = 0
  private var actions: List<() -> Unit> = emptyList()

  // What the panels draw.
  private var options by mutableStateOf(emptyList<DockOption>())
  private var xs by mutableStateOf(emptyList<Float>())
  private var highlighted by mutableStateOf<Int?>(null)
  private var pressing by mutableStateOf(false)
  private var fingerX by mutableFloatStateOf(0.5f)
  private var lift by mutableFloatStateOf(0f)
  private var bulge by mutableFloatStateOf(0f)
  private var swipe by mutableFloatStateOf(0f)
  private var hint by mutableStateOf<String?>(null)

  val expanded: Boolean get() = gesture.revealed

  /** Where the bar is drawn right now (it eases toward the view), or null while hidden. */
  fun currentPose(): Pose? = follow.pose

  fun onSceneReady() {
    if (barPanel != null) return
    val metresPerPx = xmux.config.windowWidthM / xmux.spec.widthPx
    barPanel = mount(DockPlacement.BAR_HIT_WIDTH_M, DockPlacement.BAR_HIT_HEIGHT_M, metresPerPx) {
      DockBar(bulge, swipe, barFraction = DockPlacement.BAR_WIDTH_M / DockPlacement.BAR_HIT_WIDTH_M)
    }
    bodyPanel = mount(DockPlacement.BODY_WIDTH_M, BODY_PANEL_HEIGHT_M, metresPerPx) {
      DockBody(
        options, xs, highlighted, pressing, fingerX, lift, grabberBulge = bulge, grabberSwipe = swipe,
        rowY = fromTop(DockPlacement.ROW_Y_M), grabberY = fromTop(DockPlacement.GRAB_Y_M), bodyTop = fromTop(DockPlacement.BODY_TOP_M),
        barFraction = DockPlacement.BAR_WIDTH_M / DockPlacement.BODY_WIDTH_M, hint = hint,
      )
    }.also { it.setComponent(Hittable(MeshCollision.NoCollision)) }
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
   * Shown whenever the head is tracked and [visible], easing toward the view. [pointers] are per
   * hand (0 = left, 1 = right) with their pinch state; fingertips come from the hands' joints.
   */
  fun onSceneTick(
    head: Pose?, visible: Boolean, connected: Boolean, keyboardShown: Boolean,
    pointers: List<DockGesture.Pointer?> = emptyList(), nowMs: Long = SystemClock.uptimeMillis(),
  ) {
    buildOptions(connected, keyboardShown)
    val tracked = head?.takeIf(WindowLayout::isUsableHeadPose)
    if (!visible || tracked == null) {
      hide()
      return
    }
    val dtS = lastTickMs?.let { (nowMs - it) / 1000f } ?: 0f
    lastTickMs = nowMs
    val bar = follow.update(tracked, dtS)
    val optionXs = DockLayout.optionXs(options.size)
    when (val event = gesture.update(bar, fingertips(tracked), pointers, optionXs, nowMs)) {
      DockGesture.Event.Revealed -> {
        reveals++
        Log.d(TAG, "dock raised")
      }
      is DockGesture.Event.Activated -> {
        Log.d(TAG, "dock option ${options.getOrNull(event.index)?.label}")
        actions.getOrNull(event.index)?.invoke()
      }
      DockGesture.Event.Dismissed -> Log.d(TAG, "dock lowered")
      DockGesture.Event.None -> {}
    }
    xs = optionXs.map { (it + DockPlacement.BODY_WIDTH_M / 2f) / DockPlacement.BODY_WIDTH_M }
    highlighted = gesture.highlighted
    pressing = gesture.pressing == DockGesture.Part.Option
    fingerX = (gesture.fingerX + DockPlacement.BODY_WIDTH_M / 2f) / DockPlacement.BODY_WIDTH_M
    lift = gesture.lift
    bulge = gesture.hover
    swipe = gesture.swipe
    hint = if (reveals <= HINT_REVEALS && gesture.revealed && !pressing) "Press an icon, slide, then flick up" else null
    barPanel?.setComponent(Transform(bar))
    bodyPanel?.setComponent(Transform(bar * Pose(Vector3(0f, BODY_PANEL_CENTER_M, 0f))))
    barPanel?.setComponent(Visible(!gesture.revealed))
    bodyPanel?.setComponent(Visible(gesture.revealed))
  }

  /** The options in the order they sit along the dock, with what each does. */
  private fun buildOptions(connected: Boolean, keyboardShown: Boolean) {
    val list = mutableListOf<DockOption>()
    val run = mutableListOf<() -> Unit>()
    list += DockOption(if (connected) "Connected" else "Connect", DockOptionKind.Connection, enabled = connected)
    run += onConnection
    val focused = (xmux.focused ?: xmux.windows.firstOrNull { it.visible })?.screen?.value
    for ((index, window) in xmux.windows.withIndex()) {
      val id = window.screen?.value ?: continue
      list += DockOption(window.state.title.ifBlank { "Terminal ${index + 1}" }, DockOptionKind.Window, selected = id == focused, enabled = connected)
      run += { onWindow(id) }
    }
    list += DockOption("Keyboard", DockOptionKind.Keyboard, selected = keyboardShown)
    run += onKeyboard
    list += DockOption("Arrange", DockOptionKind.Arrange, enabled = connected && xmux.windows.isNotEmpty())
    run += onArrange
    list += DockOption("Settings", DockOptionKind.Settings)
    run += onSettings
    if (list != options) options = list
    actions = run
  }

  /** Both index fingertips in scene space (their joints are tracked relative to the head). */
  private fun fingertips(head: Pose): List<Vector3?> =
    hands.map { hand ->
      hand.poll(scene(), predictedTimeNs())?.joints?.let { j ->
        head.t + head.q * Vector3(j[INDEX_TIP * 3], j[INDEX_TIP * 3 + 1], j[INDEX_TIP * 3 + 2])
      }
    }

  /** Only blocks a terminal if the visible dock is in front of it, preserving nearer panels. */
  fun blocksRay(origin: Vector3, direction: Vector3): Boolean {
    val distance = hitDistance(origin, direction) ?: return false
    val terminal = xmux.hitTest(origin, direction)?.point?.distanceTo(origin)
    return terminal == null || distance < terminal
  }

  /** Distance along a ray to the visible dock: the bar, or the raised body. */
  fun hitDistance(origin: Vector3, direction: Vector3): Float? {
    val bar = follow.pose ?: return null
    return if (gesture.revealed) {
      DockPlacement.hitDistance(DockPlacement.bodyPose(bar), origin, direction, DockPlacement.BODY_WIDTH_M, DockPlacement.BODY_TOP_M - DockPlacement.BODY_BOTTOM_M)
    } else {
      DockPlacement.hitDistance(bar, origin, direction, DockPlacement.BAR_HIT_WIDTH_M, DockPlacement.BAR_HIT_HEIGHT_M)
    }
  }

  fun hide() {
    follow.reset()
    gesture.reset()
    lastTickMs = null
    barPanel?.setComponent(Visible(false))
    bodyPanel?.setComponent(Visible(false))
  }

  fun close() {
    hide()
    hands.forEach { it.close() }
    barPanel?.destroy()
    bodyPanel?.destroy()
    barPanel = null
    bodyPanel = null
  }

  /** A dock-local height (metres above the bar) as a fraction of the body panel from its top. */
  private fun fromTop(y: Float): Float = (BODY_PANEL_TOP_M - y) / BODY_PANEL_HEIGHT_M

  private companion object {
    const val TAG = "VrcodeDock"
    const val INDEX_TIP = 10
    const val HINT_REVEALS = 3
    /** The body panel spans the raised body plus room above it for the lifted option's label. */
    const val BODY_PANEL_TOP_M = DockPlacement.BODY_TOP_M + 0.06f
    const val BODY_PANEL_HEIGHT_M = BODY_PANEL_TOP_M - DockPlacement.BODY_BOTTOM_M
    const val BODY_PANEL_CENTER_M = (BODY_PANEL_TOP_M + DockPlacement.BODY_BOTTOM_M) / 2f
  }
}
