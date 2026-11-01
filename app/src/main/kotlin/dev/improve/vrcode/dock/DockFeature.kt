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
import dev.improve.vrcode.DockTouch
import dev.improve.vrcode.DockTouchPhase
import dev.improve.vrcode.hands.HandAim
import dev.improve.vrcode.hands.IndexPinch
import dev.improve.vrcode.hands.viewToScene
import dev.improve.xmux.spatial.WindowLayout
import dev.improve.xmux.spatial.XmuxSpatial
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * The dock, used like an iPhone's home bar (see [DockGesture]): a short faint bar at the bottom of
 * the view that bulges under a hand. Pinching the bar and pulling it up raises the dock; a plain
 * touch does not, so brushing the bar never opens it. Touching an option runs it at once. The bar
 * stays where it is, underneath the raised options, and touching it again lowers the dock.
 *
 * Pinches are read from the hands' own tracking ([HandAim], the runtime's index pinch) or, without
 * tracked hands, a controller's trigger. Touches come from the Interaction SDK, as for every other
 * panel: a fingertip touching a panel (direct touch) or a pinching ray on it reaches the panel's
 * Compose content, which reports each touch as it lands (see `DockTouch`). Two pooled panels, independent of terminal reconnects: the
 * bar, always shown, and the raised body above it, shown and hittable only while raised, so it never
 * takes a touch or swallows terminal input while hidden. The bar is always rendered while the head is tracked and
 * [onSceneTick]'s `visible` is set, including while settings is open: it is how the user can tell
 * VRcode loaded.
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
  /** Each hand's index pinch on the last tick (left, right), which keeps a held pinch through dropouts. */
  private val pinching = BooleanArray(2)
  private var wasPulling = false
  private var lastTickMs: Long? = null
  private var actions: List<() -> Unit> = emptyList()
  /** Touches from the panels' Compose content, drained each tick. */
  private val touches = ConcurrentLinkedQueue<DockGesture.Touch>()
  private var showing: Showing? = null

  // What the panels draw.
  private var options by mutableStateOf(emptyList<DockOption>())
  private var xs by mutableStateOf(emptyList<Float>())
  private var highlighted by mutableStateOf<Int?>(null)
  private var bulge by mutableFloatStateOf(0f)
  private var lift by mutableFloatStateOf(0f)
  private var raised by mutableStateOf(false)

  val expanded: Boolean get() = gesture.revealed

  /** Where the bar is drawn right now (it eases toward the view), or null while hidden. */
  fun currentPose(): Pose? = follow.pose

  fun onSceneReady() {
    if (barPanel != null) return
    val metresPerPx = xmux.config.windowWidthM / xmux.spec.widthPx
    barPanel = mount(DockPlacement.BAR_HIT_WIDTH_M, DockPlacement.BAR_HIT_HEIGHT_M, metresPerPx) {
      DockBar(bulge, raised, barFraction = DockPlacement.BAR_WIDTH_M / DockPlacement.BAR_HIT_WIDTH_M, lift = lift) { queue(DockGesture.Surface.Bar, it) }
    }
    bodyPanel = mount(DockPlacement.BODY_WIDTH_M, DockPlacement.BODY_PANEL_HEIGHT_M, metresPerPx) {
      DockBody(
        options, xs, highlighted, rowY = fromTop(DockPlacement.ROW_Y_M), bodyTop = fromTop(DockPlacement.BODY_TOP_M),
      ) { queue(DockGesture.Surface.Body, it) }
    }
    showing = null
  }

  /** Called from the panels' Compose content; handled on the next scene tick. */
  private fun queue(surface: DockGesture.Surface, touch: DockTouch) {
    val (x, y) = DockPlacement.touchPoint(surface, touch.x, touch.y)
    val phase = when (touch.phase) {
      DockTouchPhase.Down -> DockGesture.Phase.Down
      DockTouchPhase.Move -> DockGesture.Phase.Move
      DockTouchPhase.Up -> DockGesture.Phase.Up
    }
    if (phase == DockGesture.Phase.Down) Log.d(TAG, "dock touch down on ${surface.name.lowercase()} at ${(x * 100).roundToInt()},${(y * 100).roundToInt()} cm")
    touches.add(DockGesture.Touch(surface, phase, x, y))
  }

  private enum class Showing { None, Collapsed, Raised }

  /** Only panels showing take touches and rays: the bar whenever the dock shows, the body while raised. */
  private fun show(which: Showing) {
    if (showing == which) return
    showing = which
    val bar = which != Showing.None
    val body = which == Showing.Raised
    barPanel?.setComponent(Hittable(if (bar) MeshCollision.LineTest else MeshCollision.NoCollision))
    bodyPanel?.setComponent(Hittable(if (body) MeshCollision.LineTest else MeshCollision.NoCollision))
    barPanel?.setComponent(Visible(bar))
    bodyPanel?.setComponent(Visible(body))
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
   * hand (0 = left, 1 = right) with their trigger state; fingertips and pinches come from the hands'
   * joints.
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
    val event = gesture.update(bar, handsNow(tracked, pointers), drainTouches(), optionXs, nowMs)
    if (gesture.pulling != wasPulling) {
      wasPulling = gesture.pulling
      if (wasPulling) Log.d(TAG, "dock bar pinched") else if (event != DockGesture.Event.Revealed) Log.d(TAG, "dock bar let go before raising")
    }
    when (event) {
      DockGesture.Event.Revealed -> {
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
    bulge = gesture.hover
    lift = gesture.lift
    raised = gesture.revealed
    barPanel?.setComponent(Transform(bar))
    bodyPanel?.setComponent(Transform(bar * Pose(Vector3(0f, DockPlacement.BODY_PANEL_CENTER_M, 0f))))
    show(if (gesture.revealed) Showing.Raised else Showing.Collapsed)
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

  private fun drainTouches(): List<DockGesture.Touch> = generateSequence { touches.poll() }.toList()

  /**
   * Both hands in scene space: each tracked hand's index fingertip, the point between its thumb and
   * index tips, and whether they pinch, with its pointer ray. The joints are tracked in OpenXR VIEW
   * space, which [viewToScene] mirrors into Spatial's +Z-forward head frame. Without tracked hands
   * (controllers), only the ray is known, and its trigger stands in for the pinch.
   */
  private fun handsNow(head: Pose, pointers: List<DockGesture.Pointer?>): List<DockGesture.Hand> =
    hands.mapIndexed { i, aim ->
      val pointer = pointers.getOrNull(i)
      val frame = aim.poll(scene(), predictedTimeNs())
      pinching[i] = frame != null && IndexPinch.down(frame, pinching[i])
      if (frame == null) return@mapIndexed DockGesture.Hand(pointer = pointer, pressed = pointer?.pressed == true)
      val joints = frame.joints
      DockGesture.Hand(
        fingertip = viewToScene(head, joints.copyOfRange(INDEX_TIP * 3, INDEX_TIP * 3 + 3)),
        pinch = viewToScene(head, IndexPinch.point(joints)),
        pointer = pointer,
        pressed = pinching[i],
      )
    }

  /** Only blocks a terminal if the visible dock is in front of it, preserving nearer panels. */
  fun blocksRay(origin: Vector3, direction: Vector3): Boolean {
    val distance = hitDistance(origin, direction) ?: return false
    val terminal = xmux.hitTest(origin, direction)?.point?.distanceTo(origin)
    return terminal == null || distance < terminal
  }

  /** Distance along a ray to the visible dock: the bar, or the raised body above it. */
  fun hitDistance(origin: Vector3, direction: Vector3): Float? {
    val bar = follow.pose ?: return null
    val onBar = DockPlacement.hitDistance(bar, origin, direction, DockPlacement.BAR_HIT_WIDTH_M, DockPlacement.BAR_HIT_HEIGHT_M)
    if (!gesture.revealed) return onBar
    val onBody = DockPlacement.hitDistance(DockPlacement.bodyPose(bar), origin, direction, DockPlacement.BODY_WIDTH_M, DockPlacement.BODY_TOP_M - DockPlacement.BODY_BOTTOM_M)
    return listOfNotNull(onBar, onBody).minOrNull()
  }

  fun hide() {
    follow.reset()
    gesture.reset()
    wasPulling = false
    lastTickMs = null
    touches.clear()
    show(Showing.None)
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
  private fun fromTop(y: Float): Float = (DockPlacement.BODY_PANEL_TOP_M - y) / DockPlacement.BODY_PANEL_HEIGHT_M

  private companion object {
    const val TAG = "VrcodeDock"
    const val INDEX_TIP = 10
  }
}
