package dev.improve.vrcode.windows

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
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

internal object EdgeBand {
  /** How far beyond each edge the band reaches. */
  const val BAND_M = 0.05f
  /** Kept clear at each end of an edge: the corner handles are there, and they scale the window. */
  const val CORNER_M = 0.07f

  fun horizontal(edge: WindowEdge) = edge == WindowEdge.Left || edge == WindowEdge.Right

  /** +1 when the edge moves outward along its axis's positive direction (right, up). */
  fun sign(edge: WindowEdge) = if (edge == WindowEdge.Right || edge == WindowEdge.Top) 1f else -1f

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
   * an edge and at least [cornerM] from its ends. Null on the window itself (its own input) or
   * anywhere else.
   */
  fun hit(origin: Vector3, direction: Vector3, pose: Pose, widthM: Float, heightM: Float,
    bandM: Float = BAND_M, cornerM: Float = CORNER_M): EdgeHit? {
    val p = planePoint(origin, direction, pose) ?: return null
    val beyondX = abs(p.x) - widthM / 2f
    val beyondY = abs(p.y) - heightM / 2f
    if (beyondX in 0f..bandM && abs(p.y) <= heightM / 2f - cornerM) {
      return EdgeHit(if (p.x > 0f) WindowEdge.Right else WindowEdge.Left, p.distance, p.x)
    }
    if (beyondY in 0f..bandM && abs(p.x) <= widthM / 2f - cornerM) {
      return EdgeHit(if (p.y > 0f) WindowEdge.Top else WindowEdge.Bottom, p.distance, p.y)
    }
    return null
  }

  /** The window's width or height once the dragged [edge] has moved from [startAlong] to [along]. */
  fun extent(edge: WindowEdge, startExtent: Float, startAlong: Float, along: Float): Float =
    startExtent + (along - startAlong) * sign(edge)
}

/**
 * Resizing a terminal window from an edge changes its columns (left, right) or rows (top, bottom)
 * in cmux; its corner handles scale it instead. Point just past an edge, where the window's border
 * lights up along it, then pinch (or pull the trigger) and drag. The window grows or shrinks with
 * the hand a whole cell at a time while the opposite edge stays put; the terminals keep their grids
 * until release, so the new space shows empty or the cut-off part is hidden. On release xmux asks
 * cmux for the grids that fill it (`TerminalGridSizing`) and the window takes their size. The drag
 * ends when the press is released or the hand is lost. Main thread only.
 */
internal class EdgeResize(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  /** False for windows that cannot be resized now (fullscreen). */
  private val resizable: (ScreenWindow) -> Boolean,
  private val onStart: (ScreenWindow) -> Unit,
) {
  private class Hand(val left: Boolean, val origin: Vector3, val direction: Vector3, val pressed: Boolean)
  private class Target(val window: ScreenWindow, val hit: EdgeHit)
  private class Drag(val window: ScreenWindow, val edge: WindowEdge, val left: Boolean, val plane: Pose,
    val startExtent: Float, val startAlong: Float)

  private var drag: Drag? = null
  private var leftWasPressed = false
  private var rightWasPressed = false
  private val highlight = EdgeHighlight()
  private var host: View? = null

  /** Hover, start, follow and end edge drags. [allowed] false ends a drag; [blocked] rays (the dock) never resize. */
  fun tick(allowed: Boolean, blocked: (Vector3, Vector3) -> Boolean) {
    val hands = if (allowed) hands() else emptyList()
    drag?.let { current ->
      val hand = hands.firstOrNull { it.left == current.left }
      if (hand == null || !hand.pressed || current.window !in xmux.windows || !current.window.visible || !resizable(current.window)) end()
      else follow(current, hand)
    }
    var shown: Target? = null
    if (drag == null) {
      for (hand in hands) {
        // A pinch that began elsewhere (a text selection, a title-bar drag) never turns into a resize.
        if (hand.pressed && wasPressed(hand.left)) continue
        val target = target(hand, blocked) ?: continue
        if (hand.pressed) {
          start(target, hand)
          break
        }
        if (shown == null) shown = target
      }
    }
    leftWasPressed = hands.any { it.left && it.pressed }
    rightWasPressed = hands.any { !it.left && it.pressed }
    val active = drag
    if (active != null) show(active.window, active.edge, dragging = true)
    else show(shown?.window, shown?.hit?.edge, dragging = false)
  }

  /** Ends any drag (applying it) and hides the highlight. */
  fun cancel() {
    end()
    show(null, null, dragging = false)
  }

  private fun wasPressed(left: Boolean) = if (left) leftWasPressed else rightWasPressed

  /** The nearest window edge band [hand] points at, unless a window body or [blocked] is in front of it. */
  private fun target(hand: Hand, blocked: (Vector3, Vector3) -> Boolean): Target? {
    if (blocked(hand.origin, hand.direction)) return null
    var best: Target? = null
    for (window in xmux.windows) {
      if (!window.visible || window.screen == null || !resizable(window)) continue
      val pose = window.pose() ?: continue
      val hit = EdgeBand.hit(hand.origin, hand.direction, pose, window.widthM, window.heightM) ?: continue
      if (best == null || hit.distance < best.hit.distance) best = Target(window, hit)
    }
    val found = best ?: return null
    val body = xmux.hitTest(hand.origin, hand.direction)?.point?.let { (it - hand.origin).length() }
    return found.takeUnless { body != null && body < found.hit.distance }
  }

  private fun start(target: Target, hand: Hand) {
    val window = target.window
    val pose = window.pose() ?: return
    val edge = target.hit.edge
    val extent = if (EdgeBand.horizontal(edge)) window.widthM else window.heightM
    drag = Drag(window, edge, hand.left, pose, extent, target.hit.along)
    onStart(window)
    Log.i(TAG, "edge resize: screen ${window.screen?.value} ${edge.name.lowercase()} edge by the ${side(hand.left)} hand " +
      "at ${metres(window.widthM)} x ${metres(window.heightM)} (scale ${String.format(Locale.US, "%.2f", window.scale)})")
  }

  private fun follow(drag: Drag, hand: Hand) {
    val point = EdgeBand.planePoint(hand.origin, hand.direction, drag.plane) ?: return
    val along = if (EdgeBand.horizontal(drag.edge)) point.x else point.y
    xmux.dragEdge(drag.window, drag.edge, EdgeBand.extent(drag.edge, drag.startExtent, drag.startAlong, along))
  }

  private fun end() {
    val current = drag ?: return
    drag = null
    if (current.window in xmux.windows) xmux.endEdgeDrag(current.window)
    Log.i(TAG, "edge resize: screen ${current.window.screen?.value} released at " +
      "${metres(current.window.widthM)} x ${metres(current.window.heightM)}")
  }

  /** Lights [edge] of [window] (none when null), brighter while it is being dragged. */
  private fun show(window: ScreenWindow?, edge: WindowEdge?, dragging: Boolean) {
    val root = if (edge != null) window?.let { (it.screenView ?: it.view)?.rootView } else null
    if (root !== host) {
      host?.overlay?.remove(highlight)
      host = root
      root?.overlay?.add(highlight)
    }
    if (root == null || window == null || edge == null) return
    val pxPerM = root.width / window.widthM
    highlight.show(root.width, root.height, edge, dragging, cornerPx = EdgeBand.CORNER_M * pxPerM, thicknessPx = THICKNESS_M * pxPerM)
  }

  /** Each tracked hand or controller's pointer ray and whether its pinch or trigger is held. */
  private fun hands(): List<Hand> {
    val body = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()
      ?: return emptyList()
    return listOf(Triple(false, body.rightHand, ButtonBits.ButtonTriggerR), Triple(true, body.leftHand, ButtonBits.ButtonTriggerL))
      .mapNotNull { (left, entity, trigger) ->
        val controller = entity.tryGetComponent<Controller>()?.takeIf { it.isActive } ?: return@mapNotNull null
        val pose = entity.tryGetComponent<Transform>()?.transform ?: return@mapNotNull null
        Hand(left, pose.t, pose.forward(), controller.buttonState and trigger != 0)
      }
  }

  private companion object {
    const val TAG = "VrcodeWindow"
    /** The lit border's width. */
    const val THICKNESS_M = 0.004f

    fun side(left: Boolean) = if (left) "left" else "right"
    fun metres(value: Float) = String.format(Locale.US, "%.2f m", value)
  }
}

/** A rounded bar along one edge of a panel, inside its border: the edge a pinch would resize from. */
private class EdgeHighlight : Drawable() {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val bar = RectF()
  private var key: List<Any>? = null

  fun show(width: Int, height: Int, edge: WindowEdge, dragging: Boolean, cornerPx: Float, thicknessPx: Float) {
    val next = listOf(width, height, edge, dragging, cornerPx, thicknessPx)
    if (next == key) return
    key = next
    setBounds(0, 0, width, height)
    val inset = thicknessPx / 2f
    when (edge) {
      WindowEdge.Left -> bar.set(inset, cornerPx, inset + thicknessPx, height - cornerPx)
      WindowEdge.Right -> bar.set(width - inset - thicknessPx, cornerPx, width - inset, height - cornerPx)
      WindowEdge.Top -> bar.set(cornerPx, inset, width - cornerPx, inset + thicknessPx)
      WindowEdge.Bottom -> bar.set(cornerPx, height - inset - thicknessPx, width - cornerPx, height - inset)
    }
    paint.color = if (dragging) 0xFF7DC7FF.toInt() else 0xB3FFFFFF.toInt()
    invalidateSelf()
  }

  override fun draw(canvas: Canvas) {
    if (bar.isEmpty) return
    val radius = minOf(bar.width(), bar.height()) / 2f
    canvas.drawRoundRect(bar, radius, radius, paint)
  }

  override fun setAlpha(alpha: Int) = Unit
  override fun setColorFilter(colorFilter: ColorFilter?) = Unit
  @Deprecated("Deprecated in Java")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
