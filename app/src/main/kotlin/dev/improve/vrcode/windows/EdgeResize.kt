package dev.improve.vrcode.windows

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.hands.HandAim
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.WindowEdge
import dev.improve.xmux.spatial.XmuxSpatial
import java.util.Locale
import kotlin.math.abs

/**
 * Where a ray crosses the band just outside one edge of a window: [along] is the crossing's
 * distance from the window's centre along the axis that edge moves on (local X for Left and Right,
 * local Y, up, for Top and Bottom).
 */
internal data class EdgeHit(val edge: WindowEdge, val distance: Float, val along: Float)

/** Where a ray meets a panel's plane, in the panel's local axes (X right, Y up) from its centre. */
internal class PlanePoint(val x: Float, val y: Float, val distance: Float)

/** The lit bar beside an edge: its centre in the window's local axes and its size, in metres. */
internal class EdgeBarShape(val centre: Vector3, val size: Vector3)

internal object EdgeBand {
  /** How far beyond each edge the band reaches, at least: the width at arm's length. */
  const val BAND_M = 0.05f
  /** Beyond that the band widens with distance, about 3.4°, so a far window's edge stays as easy to hit. */
  const val BAND_RAD = 0.06f
  const val MAX_BAND_M = 0.25f
  /** Kept clear at each end of an edge: the corner handles are there, and they scale the window. */
  const val CORNER_M = 0.07f
  /** The bar's width, about 0.23° of view, kept readable up close and slim far away. */
  const val BAR_RAD = 0.004f
  const val MIN_BAR_M = 0.004f
  const val MAX_BAR_M = 0.014f
  /** The bar's depth, and how far it sits toward the viewer from the window's plane. */
  const val BAR_DEPTH_M = 0.002f
  /** The bar's centre, as a share of the band out from the edge. */
  const val BAR_OUT = 0.3f

  /**
   * The edges a pointer resizes from. The top is the title bar's side and only moves the window,
   * so no band lies above it.
   */
  val RESIZABLE = listOf(WindowEdge.Left, WindowEdge.Right, WindowEdge.Bottom)

  fun horizontal(edge: WindowEdge) = edge == WindowEdge.Left || edge == WindowEdge.Right

  /** +1 when the edge moves outward along its axis's positive direction (right, up). */
  fun sign(edge: WindowEdge) = if (edge == WindowEdge.Right || edge == WindowEdge.Top) 1f else -1f

  /** How far past an edge the band reaches on a window [distanceM] away. */
  fun bandM(distanceM: Float): Float = (distanceM * BAND_RAD).coerceIn(BAND_M, MAX_BAND_M)

  /** The ray's crossing with the plane of a panel at [pose]; null when it is parallel or behind the ray. */
  fun planePoint(origin: Vector3, direction: Vector3, pose: Pose): PlanePoint? {
    val length = direction.length()
    if (!(length > 0f && length.isFinite())) return null
    val dir = direction * (1f / length)
    val normal = pose.q.times(Vector3(0f, 0f, 1f))
    val facing = dir.dot(normal)
    if (abs(facing) < 1e-6f) return null
    val distance = (pose.t - origin).dot(normal) / facing
    if (!(distance >= 0f)) return null
    val local = origin + dir * distance - pose.t
    return PlanePoint(local.dot(pose.q.times(Vector3(1f, 0f, 0f))), local.dot(pose.q.times(Vector3(0f, 1f, 0f))), distance)
  }

  /**
   * The edge band a ray points at on a [widthM] x [heightM] window at [pose]: up to [bandM] beyond
   * a [RESIZABLE] edge (by default [EdgeBand.bandM] for the ray's distance) and at least [cornerM]
   * from its ends. Null on the window itself (its own input), above it, or anywhere else.
   */
  fun hit(origin: Vector3, direction: Vector3, pose: Pose, widthM: Float, heightM: Float,
    bandM: Float? = null, cornerM: Float = CORNER_M): EdgeHit? {
    val p = planePoint(origin, direction, pose) ?: return null
    val band = bandM ?: bandM(p.distance)
    val beyondX = abs(p.x) - widthM / 2f
    val beyondY = abs(p.y) - heightM / 2f
    if (beyondX in 0f..band && abs(p.y) <= heightM / 2f - cornerM) {
      return EdgeHit(if (p.x > 0f) WindowEdge.Right else WindowEdge.Left, p.distance, p.x)
    }
    if (beyondY in 0f..band && abs(p.x) <= widthM / 2f - cornerM) {
      val edge = if (p.y > 0f) WindowEdge.Top else WindowEdge.Bottom
      if (edge in RESIZABLE) return EdgeHit(edge, p.distance, p.y)
    }
    return null
  }

  /**
   * Twice the band: keeps an edge the ray drifted off while a pinch closed. Still never on the
   * window, so a press that lands on it stays the window's (a click, a title-bar drag).
   */
  fun nearHit(origin: Vector3, direction: Vector3, pose: Pose, widthM: Float, heightM: Float): EdgeHit? {
    val distance = planePoint(origin, direction, pose)?.distance ?: return null
    return hit(origin, direction, pose, widthM, heightM, bandM = bandM(distance) * 2f)
  }

  /**
   * How far along the ray it crosses the band all around a [widthM] x [heightM] window at [pose],
   * top and corners included: just short of the window, where the left hand's pinch still grabs it
   * to move it ([LeftHandMove]). Null on the window itself or beyond the band ([bandM], by default
   * [EdgeBand.bandM] for the ray's distance).
   */
  fun around(origin: Vector3, direction: Vector3, pose: Pose, widthM: Float, heightM: Float, bandM: Float? = null): Float? {
    val p = planePoint(origin, direction, pose) ?: return null
    val band = bandM ?: bandM(p.distance)
    val beyondX = abs(p.x) - widthM / 2f
    val beyondY = abs(p.y) - heightM / 2f
    if (beyondX > band || beyondY > band || (beyondX < 0f && beyondY < 0f)) return null
    return p.distance
  }

  /** The window's width or height once the dragged [edge] has moved from [startAlong] to [along]. */
  fun extent(edge: WindowEdge, startExtent: Float, startAlong: Float, along: Float): Float =
    startExtent + (along - startAlong) * sign(edge)

  /**
   * The bar that lights [edge] of a [widthM] x [heightM] window seen from [distanceM]: outside the
   * window, in the inner part of the band (aiming at it leaves room for the pinch's dip before the
   * ray reaches the window), and as long as the edge minus the corner handles.
   */
  fun bar(edge: WindowEdge, widthM: Float, heightM: Float, distanceM: Float): EdgeBarShape {
    val thickness = (distanceM * BAR_RAD).coerceIn(MIN_BAR_M, MAX_BAR_M)
    val out = maxOf(thickness * 1.5f, bandM(distanceM) * BAR_OUT)
    val s = sign(edge)
    // A panel's visible face is its local -Z, so -Z is toward the viewer.
    val lift = -BAR_DEPTH_M
    return if (horizontal(edge)) {
      val length = (heightM - 2f * CORNER_M).coerceAtLeast(thickness)
      EdgeBarShape(Vector3(s * (widthM / 2f + out), 0f, lift), Vector3(thickness, length, BAR_DEPTH_M))
    } else {
      val length = (widthM - 2f * CORNER_M).coerceAtLeast(thickness)
      EdgeBarShape(Vector3(0f, s * (heightM / 2f + out), lift), Vector3(length, thickness, BAR_DEPTH_M))
    }
  }
}

/**
 * One hand's pinch as an edge drag reads it. A tracked hand's ray dips as the fingers close, and
 * its pinch can drop for a frame or two while the hand moves. So a press grabs the edge under the
 * ray, or one the ray left within [hoverGraceMs] if it is still [near] it; and a drag outlasts a
 * release shorter than [releaseGraceMs]. A press that started anywhere else never grabs.
 */
internal class EdgeGrip<T : Any>(
  private val hoverGraceMs: Long = HOVER_GRACE_MS,
  private val releaseGraceMs: Long = RELEASE_GRACE_MS,
) {
  private var hovered: T? = null
  private var hoveredAtMs = 0L
  /** Until the hand is seen open, a press it holds is not a new one. */
  private var wasPressed = true
  private var releasedAtMs: Long? = null

  /** The edge the last open-hand frame pointed at, and how long ago, for logs. */
  fun hoverAgeMs(nowMs: Long): Long? = hovered?.let { nowMs - hoveredAtMs }

  /**
   * One frame of a hand that is not dragging, its ray on [target] (null for none). Returns the
   * edge to start dragging when its press starts this frame.
   */
  fun press(nowMs: Long, pressed: Boolean, target: T?, near: (T) -> Boolean): T? {
    val started = pressed && !wasPressed
    wasPressed = pressed
    if (!pressed) {
      if (target != null) {
        hovered = target
        hoveredAtMs = nowMs
      }
      return null
    }
    if (!started) return null
    val latched = hovered?.takeIf { nowMs - hoveredAtMs <= hoverGraceMs && near(it) }
    hovered = null
    releasedAtMs = null
    return target ?: latched
  }

  /** One frame holding a drag (null when tracking is lost): false once the drag should end. */
  fun hold(nowMs: Long, pressed: Boolean?): Boolean {
    // Missing tracking cannot arm a fresh pinch when the hand returns still closed.
    wasPressed = pressed != false
    if (pressed == true) {
      releasedAtMs = null
      return true
    }
    val since = releasedAtMs ?: nowMs.also { releasedAtMs = it }
    return nowMs - since < releaseGraceMs
  }

  /** The hand is not tracked: forget what it pointed at, and wait for it to open before a new press. */
  fun lost() {
    hovered = null
    wasPressed = true
  }

  companion object {
    const val HOVER_GRACE_MS = 300L
    const val RELEASE_GRACE_MS = 150L
  }
}

/**
 * Resizing a terminal window from an edge changes its columns (left, right) or rows (bottom) in
 * cmux; its corner handles scale it instead, and its top (the title bar) only moves it. Only the
 * right hand (or controller) resizes. Point just past a resizable edge, where a bar lights up
 * beside it, then pinch (or pull the trigger) and drag. The window grows or shrinks with the hand
 * a whole cell at a time while the opposite edge stays put; the terminals keep their grids until
 * release, so the new space shows empty or the cut-off part is hidden. On release xmux asks cmux
 * for the grids that fill it (`TerminalGridSizing`) and the window takes their size. The band past
 * each edge widens with distance, and [EdgeGrip] rides out the pinch's dip and flicker. The drag
 * ends when the press has been released (or the hand lost) for a moment. Main thread only.
 */
@OptIn(SpatialSDKExperimentalAPI::class)
internal class EdgeResize(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val predictedTimeNs: () -> Long,
  /** False for windows that cannot be resized now (fullscreen). */
  private val resizable: (ScreenWindow) -> Boolean,
  private val onStart: (ScreenWindow) -> Unit,
) : AutoCloseable {
  private class Hand(val left: Boolean, val origin: Vector3, val direction: Vector3, val pressed: Boolean,
    val laserEnabled: Boolean)
  private class Target(val window: ScreenWindow, val hit: EdgeHit)
  private class Drag(val window: ScreenWindow, val edge: WindowEdge, val left: Boolean, val plane: Pose,
    val startExtent: Float, val startAlong: Float) {
    var distance = 0f
  }

  private var drag: Drag? = null
  private val leftGrip = EdgeGrip<Target>()
  private val rightGrip = EdgeGrip<Target>()
  private val bar = EdgeBar()
  private val aims = mapOf(true to HandAim(left = true), false to HandAim(left = false))
  private val presses = mapOf(true to EdgePress(left = true), false to EdgePress(left = false))
  private val pointers = mapOf(true to EdgePointer(), false to EdgePointer())

  init {
    Log.i(TAG, "edge resize input: the right hand's runtime index pinch, with a focus dot on the edge bands")
  }

  /** Hover, start, follow and end edge drags. [allowed] false ends a drag; [blocked] rays (the dock) never resize. */
  fun tick(allowed: Boolean, blocked: (Vector3, Vector3) -> Boolean) {
    val now = SystemClock.uptimeMillis()
    val hands = if (allowed) hands() else emptyList()
    for (left in listOf(true, false)) if (hands.none { it.left == left }) {
      grip(left).lost()
      presses.getValue(left).reset()
    }
    drag?.let { current ->
      val hand = hands.firstOrNull { it.left == current.left }
      val reason = when {
        !allowed -> "input paused"
        current.window !in xmux.windows || !current.window.visible || !resizable(current.window) -> "window gone"
        !grip(current.left).hold(now, hand?.pressed) -> if (hand == null) "hand lost" else "released"
        else -> null
      }
      if (reason != null) end(reason)
      else if (hand != null && hand.pressed) follow(current, hand)
    }
    var shown: Target? = null
    for (hand in hands) {
        if (drag?.left == hand.left) continue
        val target = target(hand, blocked)
        val grip = grip(hand.left)
        val hoverAge = grip.hoverAgeMs(now)
        val grabbed = grip.press(now, hand.pressed, target) { near(it, hand, blocked) }
        if (grabbed != null && drag == null) {
          start(grabbed, hand, latched = if (target == null) hoverAge else null)
        }
        if (!hand.pressed && shown == null) shown = target
    }
    val active = drag
    if (active != null) show(active.window, active.edge, active.distance, dragging = true)
    else if (shown != null) show(shown.window, shown.hit.edge, shown.hit.distance, dragging = false)
    else bar.hide()
    // No panel exists past an edge for the SDK's dot to land on. Keep the aiming feedback there
    // for either hand, including a held drag; never turn these graphics into interactables.
    for ((left, pointer) in pointers) {
      val hand = hands.firstOrNull { it.left == left && it.laserEnabled }
      val holding = active?.takeIf { it.left == left }
      val distance = hand?.let {
        if (holding != null) holding.window.pose()?.let { pose -> EdgeBand.planePoint(it.origin, it.direction, pose)?.distance }
        else target(it, blocked)?.hit?.distance
      }
      if (hand != null && distance != null) pointer.show(hand.origin, hand.direction, distance, holding != null)
      else pointer.hide()
    }
  }

  /** Ends any drag (applying it) and hides the bar. */
  fun cancel() {
    end("cancelled")
    leftGrip.lost()
    rightGrip.lost()
    presses.values.forEach { it.reset() }
    pointers.values.forEach { it.hide() }
    bar.hide()
  }

  override fun close() {
    cancel()
    aims.values.forEach { it.close() }
    pointers.values.forEach { it.close() }
    bar.close()
  }

  private fun grip(left: Boolean) = if (left) leftGrip else rightGrip

  /** The nearest window edge band [hand] points at, unless a window body or [blocked] is in front of it. */
  private fun target(hand: Hand, blocked: (Vector3, Vector3) -> Boolean): Target? {
    if (blocked(hand.origin, hand.direction)) return null
    var best: Target? = null
    for (window in xmux.windows) {
      if (!canResize(window)) continue
      val pose = window.pose() ?: continue
      val hit = EdgeBand.hit(hand.origin, hand.direction, pose, window.widthM, window.heightM) ?: continue
      if (best == null || hit.distance < best.hit.distance) best = Target(window, hit)
    }
    val found = best ?: return null
    val body = xmux.hitTest(hand.origin, hand.direction)?.point?.let { (it - hand.origin).length() }
    return found.takeUnless { body != null && body < found.hit.distance }
  }

  /** True while [hand] still points close to the edge [target] it just left. */
  private fun near(target: Target, hand: Hand, blocked: (Vector3, Vector3) -> Boolean): Boolean {
    val window = target.window
    if (window !in xmux.windows || !canResize(window) || blocked(hand.origin, hand.direction)) return false
    val pose = window.pose() ?: return false
    return EdgeBand.nearHit(hand.origin, hand.direction, pose, window.widthM, window.heightM)?.edge == target.hit.edge
  }

  private fun canResize(window: ScreenWindow) = window.visible && window.screen != null && resizable(window)

  private fun start(target: Target, hand: Hand, latched: Long?) {
    val window = target.window
    val pose = window.pose() ?: return
    val edge = target.hit.edge
    val extent = if (EdgeBand.horizontal(edge)) window.widthM else window.heightM
    // Measured from where the ray is now, so the window does not jump when the press latched an edge the ray had left.
    val point = EdgeBand.planePoint(hand.origin, hand.direction, pose)
    val along = point?.let { if (EdgeBand.horizontal(edge)) it.x else it.y } ?: target.hit.along
    drag = Drag(window, edge, hand.left, pose, extent, along).also { it.distance = point?.distance ?: target.hit.distance }
    onStart(window)
    Log.i(TAG, "edge resize: screen ${window.screen?.value} ${edge.name.lowercase()} edge by the ${side(hand.left)} hand " +
      "at ${metres(window.widthM)} x ${metres(window.heightM)}, ${metres(target.hit.distance)} away " +
      "(scale ${String.format(Locale.US, "%.2f", window.scale)}${latched?.let { ", the ray left the edge $it ms before the pinch" } ?: ""})")
  }

  private fun follow(drag: Drag, hand: Hand) {
    val point = EdgeBand.planePoint(hand.origin, hand.direction, drag.plane) ?: return
    drag.distance = point.distance
    val along = if (EdgeBand.horizontal(drag.edge)) point.x else point.y
    xmux.dragEdge(drag.window, drag.edge, EdgeBand.extent(drag.edge, drag.startExtent, drag.startAlong, along))
  }

  private fun end(reason: String) {
    val current = drag ?: return
    drag = null
    if (current.window in xmux.windows) xmux.endEdgeDrag(current.window)
    Log.i(TAG, "edge resize: screen ${current.window.screen?.value} $reason at " +
      "${metres(current.window.widthM)} x ${metres(current.window.heightM)}")
  }

  /** Lights [edge] of [window], seen from [distance], brighter while it is being dragged. */
  private fun show(window: ScreenWindow, edge: WindowEdge, distance: Float, dragging: Boolean) {
    val pose = window.pose()
    if (pose == null) {
      bar.hide()
      return
    }
    val shape = EdgeBand.bar(edge, window.widthM, window.heightM, distance)
    bar.show(Pose(pose.t + pose.q.times(shape.centre), pose.q), shape.size, dragging)
  }

  /**
   * Read tracked pinches directly: a hand outside a panel has no SDK trigger event. Only the right
   * hand resizes; the left hand's pinch only moves windows ([LeftHandMove]).
   */
  private fun hands(): List<Hand> {
    val body = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()
      ?: return emptyList()
    return listOf(false to body.rightHand)
      .mapNotNull { (left, entity) ->
        val controller = entity.tryGetComponent<Controller>()?.takeIf { it.isActive } ?: return@mapNotNull null
        val pose = entity.tryGetComponent<Transform>()?.transform ?: return@mapNotNull null
        val isHand = controller.type == ControllerType.HAND
        val frame = if (isHand) aims.getValue(left).poll(activity.scene, predictedTimeNs()) else null
        val pressed = presses.getValue(left).update(isHand, controller.buttonState, frame) ?: return@mapNotNull null
        Hand(left, pose.t, pose.forward(), pressed, controller.laserEnabled)
      }
  }

  private companion object {
    const val TAG = "VrcodeWindow"

    fun side(left: Boolean) = if (left) "left" else "right"
    fun metres(value: Float) = String.format(Locale.US, "%.2f m", value)
  }
}

/**
 * The bar beside a window's edge that shows where a pinch would resize from: an unlit box mesh in
 * the room, outside the window's panel, that never takes a ray. Window panels are compositor
 * layers and draw over meshes, so it sits clear of the window it belongs to.
 */
private class EdgeBar {
  private var entity: Entity? = null
  private var visible = false
  private var dragging = false
  private var pose: Pose? = null
  private var size: Vector3? = null

  fun show(pose: Pose, size: Vector3, dragging: Boolean) {
    val e = entity
    if (e == null) {
      entity = Entity.create(
        listOf(
          Mesh(Uri.parse("mesh://box"), MeshCollision.NoCollision),
          Box(Vector3(-0.5f, -0.5f, -0.5f), Vector3(0.5f, 0.5f, 0.5f)),
          material(dragging),
          Transform(pose),
          Scale(size),
          Visible(true),
        ),
      )
      this.visible = true
      this.dragging = dragging
      this.pose = pose
      this.size = size
      return
    }
    if (this.pose != pose) e.setComponent(Transform(pose)).also { this.pose = pose }
    if (this.size != size) e.setComponent(Scale(size)).also { this.size = size }
    if (this.dragging != dragging) e.setComponent(material(dragging)).also { this.dragging = dragging }
    if (!visible) e.setComponent(Visible(true)).also { visible = true }
  }

  fun hide() {
    if (!visible) return
    entity?.setComponent(Visible(false))
    visible = false
  }

  fun close() {
    entity?.destroy()
    entity = null
    visible = false
    pose = null
    size = null
  }

  private fun material(dragging: Boolean) = Material().apply {
    baseColor = if (dragging) DRAG_COLOR else HOVER_COLOR
    unlit = true
  }

  private companion object {
    val HOVER_COLOR = Color4(0.86f, 0.88f, 0.92f, 1f)
    /** #7DC7FF, the drag colour the in-panel highlight used. */
    val DRAG_COLOR = Color4(0.49f, 0.78f, 1f, 1f)
  }
}
