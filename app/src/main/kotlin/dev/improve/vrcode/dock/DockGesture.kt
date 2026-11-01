package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.math.min

/**
 * Where the raised dock's options sit: a row in dock-local metres (+X to the user's right),
 * centred on the bar and at most [MAX_SPAN_M] wide.
 */
internal object DockLayout {
  const val MAX_SPACING_M = 0.06f
  const val MAX_SPAN_M = 0.42f

  fun optionXs(count: Int): FloatArray {
    if (count <= 0) return FloatArray(0)
    val spacing = spacing(count)
    return FloatArray(count) { (it - (count - 1) / 2f) * spacing }
  }

  fun spacing(count: Int): Float = min(MAX_SPACING_M, MAX_SPAN_M / count.coerceAtLeast(1))
}

/**
 * The dock works like an iPhone's home bar, by touch:
 * 1. Collapsed, only the bar shows. A fingertip (or pointer) near it makes it bulge ([hover]).
 * 2. Pressing the bar and swiping up raises the dock ([Event.Revealed]).
 * 3. Raised, pressing an option, sliding sideways to another and flicking up activates it
 *    ([Event.Activated]); the dock then lowers. Pulling the finger back cancels the press.
 * 4. The bar sits at the raised dock's top centre as a grabber: pressing it and swiping down
 *    lowers the dock ([Event.Dismissed]), as does leaving it alone for [IDLE_MS].
 *
 * A touch needs the fingertip to reach the dock's plane from in front, inside the touched part, so
 * hands typing below the dock or a finger behind it never press it. Pointer rays work the same way
 * with a pinch, from at least [MIN_POINT_M] away. All positions are dock-local (see
 * [DockPlacement]): X right, Y up, Z away from the user, the plane at Z = 0.
 */
internal class DockGesture {
  enum class Part { Bar, Option, Grabber }

  sealed interface Event {
    data object None : Event

    data object Revealed : Event

    data class Activated(val index: Int) : Event

    data object Dismissed : Event
  }

  /** A pointer ray and whether its hand is pinching. */
  data class Pointer(val origin: Vector3, val direction: Vector3, val pinched: Boolean)

  var revealed = false
    private set

  /** The part being pressed, if any. */
  var pressing: Part? = null
    private set

  /** 0..1: how close a fingertip or pointer is to the bar (collapsed) or grabber (raised). */
  var hover = 0f
    private set

  /** 0..1: progress of the swipe up from the bar or down from the grabber. */
  var swipe = 0f
    private set

  var highlighted: Int? = null
    private set

  /** Local X of the pressing finger (or ray), and 0..1 progress of a flick up from an option. */
  var fingerX = 0f
    private set

  var lift = 0f
    private set

  private var byRay = false
  private var hand = -1
  private var pressX = 0f
  private var pressY = 0f
  private var pressedAtMs = 0L
  private var lostSinceMs: Long? = null
  private var lastNearMs = 0L
  private val previousZ = arrayOfNulls<Float>(2)
  private val previousPinch = BooleanArray(2)

  /**
   * One frame. [fingertips] and [pointers] are per hand (0 = left, 1 = right), null when that hand
   * is not tracked; [optionXs] comes from [DockLayout].
   */
  fun update(bar: Pose, fingertips: List<Vector3?>, pointers: List<Pointer?>, optionXs: FloatArray, nowMs: Long): Event {
    val inverse = bar.inverse()
    val tips = List(2) { i -> fingertips.getOrNull(i)?.let { inverse.q * it + inverse.t } }
    val hits = List(2) { i -> pointers.getOrNull(i)?.let { aimed(inverse, it) } }
    hover = hoverAmount(tips, hits)
    val event = if (pressing != null) follow(tips, pointers, hits, optionXs, nowMs) else detectPress(tips, pointers, hits, optionXs, nowMs)
    if (revealed && pressing == null && event == Event.None) {
      if (tips.any { it != null && near(it, optionXs) } || hits.any { it != null && onBody(it, optionXs) }) lastNearMs = nowMs
      if (nowMs - lastNearMs > IDLE_MS) return lower(Event.Dismissed)
    }
    for (i in 0..1) {
      previousZ[i] = tips[i]?.z
      previousPinch[i] = pointers.getOrNull(i)?.pinched == true
    }
    return event
  }

  /** Lowers the dock without an event (e.g. VRcode paused). */
  fun reset() {
    lower(Event.None)
    for (i in 0..1) {
      previousZ[i] = null
      previousPinch[i] = false
    }
  }

  private fun detectPress(tips: List<Vector3?>, pointers: List<Pointer?>, hits: List<Vector3?>, optionXs: FloatArray, nowMs: Long): Event {
    for (i in 0..1) {
      val tip = tips[i] ?: continue
      val before = previousZ[i] ?: continue
      if (before >= -FRONT_M || tip.z < -CONTACT_M || tip.z > THROUGH_M) continue
      val part = partAt(tip, optionXs) ?: continue
      return press(part, byRay = false, index = i, at = tip, optionXs = optionXs, nowMs = nowMs)
    }
    for (i in 0..1) {
      val pointer = pointers.getOrNull(i) ?: continue
      val hit = hits[i] ?: continue
      if (!pointer.pinched || previousPinch[i]) continue
      val part = partAt(hit, optionXs) ?: continue
      return press(part, byRay = true, index = i, at = hit, optionXs = optionXs, nowMs = nowMs)
    }
    return Event.None
  }

  private fun press(part: Part, byRay: Boolean, index: Int, at: Vector3, optionXs: FloatArray, nowMs: Long): Event {
    pressing = part
    this.byRay = byRay
    hand = index
    pressX = at.x
    pressY = at.y
    fingerX = at.x
    lift = 0f
    swipe = 0f
    pressedAtMs = nowMs
    lostSinceMs = null
    lastNearMs = nowMs
    if (part == Part.Option) highlighted = nearest(optionXs, at.x, null)
    return Event.None
  }

  private fun follow(tips: List<Vector3?>, pointers: List<Pointer?>, hits: List<Vector3?>, optionXs: FloatArray, nowMs: Long): Event {
    if (byRay && pointers.getOrNull(hand)?.pinched != true) return release()
    val point = if (byRay) hits[hand] else tips[hand]
    if (point == null) {
      val since = lostSinceMs ?: nowMs.also { lostSinceMs = it }
      return if (nowMs - since > LOST_MS) release() else Event.None
    }
    lostSinceMs = null
    lastNearMs = nowMs
    fingerX = point.x
    val rise = point.y - pressY
    val retreated = !byRay && point.z < -RETREAT_M
    when (pressing) {
      Part.Bar -> {
        swipe = (rise / REVEAL_M).coerceIn(0f, 1f)
        if (rise >= REVEAL_M) {
          release()
          revealed = true
          lastNearMs = nowMs
          return Event.Revealed
        }
      }
      Part.Grabber -> {
        swipe = (-rise / DISMISS_M).coerceIn(0f, 1f)
        if (-rise >= DISMISS_M) return lower(Event.Dismissed)
      }
      Part.Option -> {
        lift = (rise / FLICK_M).coerceIn(0f, 1f)
        highlighted = nearest(optionXs, point.x, highlighted)
        val picked = highlighted
        if (rise >= FLICK_M && picked != null) return lower(Event.Activated(picked))
        if (rise < -DOWN_M) return release()
      }
      null -> return Event.None
    }
    if (retreated || nowMs - pressedAtMs > TIMEOUT_MS) return release()
    return Event.None
  }

  /** Ends the current press; the dock stays as it is. */
  private fun release(): Event {
    pressing = null
    hand = -1
    lift = 0f
    swipe = 0f
    lostSinceMs = null
    if (!revealed) highlighted = null
    return Event.None
  }

  private fun lower(event: Event): Event {
    release()
    revealed = false
    highlighted = null
    return event
  }

  /** The part a point on the dock's plane is over, given the dock's state. */
  private fun partAt(p: Vector3, optionXs: FloatArray): Part? =
    if (!revealed) {
      Part.Bar.takeIf { abs(p.x) <= DockPlacement.BAR_HIT_WIDTH_M / 2f && abs(p.y) <= DockPlacement.BAR_HIT_HEIGHT_M / 2f }
    } else {
      when {
        abs(p.x) <= DockPlacement.BAR_HIT_WIDTH_M / 2f && abs(p.y - DockPlacement.GRAB_Y_M) <= GRAB_HALF_HEIGHT_M -> Part.Grabber
        abs(p.x) <= rowHalfWidth(optionXs) && abs(p.y - DockPlacement.ROW_Y_M) <= ROW_HALF_HEIGHT_M -> Part.Option
        else -> null
      }
    }

  private fun rowHalfWidth(optionXs: FloatArray): Float = (optionXs.lastOrNull() ?: 0f) + DockLayout.MAX_SPACING_M / 2f

  private fun onBody(p: Vector3, optionXs: FloatArray): Boolean =
    abs(p.x) <= rowHalfWidth(optionXs) + 0.02f && p.y >= DockPlacement.BODY_BOTTOM_M - 0.02f && p.y <= DockPlacement.BODY_TOP_M + 0.02f

  private fun near(tip: Vector3, optionXs: FloatArray): Boolean = tip.z >= -NEAR_M && tip.z <= THROUGH_M && onBody(tip, optionXs)

  /** How close a fingertip in front of the bar (or grabber), or a pointer on it, is: 0 far, 1 touching. */
  private fun hoverAmount(tips: List<Vector3?>, hits: List<Vector3?>): Float {
    val centreY = if (revealed) DockPlacement.GRAB_Y_M else 0f
    var best = 0f
    for (tip in tips) {
      if (tip == null || abs(tip.x) > HOVER_HALF_WIDTH_M || abs(tip.y - centreY) > HOVER_HALF_HEIGHT_M) continue
      if (tip.z > THROUGH_M || tip.z < -HOVER_M) continue
      best = maxOf(best, (1f - (-tip.z).coerceAtLeast(0f) / HOVER_M).coerceIn(0f, 1f))
    }
    for (hit in hits) {
      if (hit != null && abs(hit.x) <= DockPlacement.BAR_HIT_WIDTH_M / 2f && abs(hit.y - centreY) <= DockPlacement.BAR_HIT_HEIGHT_M / 2f) {
        best = maxOf(best, POINTER_HOVER)
      }
    }
    return best
  }

  /** Where a ray from at least [MIN_POINT_M] away crosses the dock's plane (local), ignoring extents. */
  private fun aimed(inverse: Pose, pointer: Pointer): Vector3? {
    val origin = inverse.q * pointer.origin + inverse.t
    val direction = inverse.q * pointer.direction.normalize()
    if (direction.z <= 0.0001f) return null
    val t = -origin.z / direction.z
    // Pointing at something within arm's reach from a few centimetres is a typing hand's ray.
    if (t < MIN_POINT_M) return null
    return origin + direction * t
  }

  /** Nearest option to [x]; keeps [current] until the finger is clearly nearer another. */
  private fun nearest(optionXs: FloatArray, x: Float, current: Int?): Int? {
    if (optionXs.isEmpty()) return null
    val spacing = if (optionXs.size > 1) optionXs[1] - optionXs[0] else DockLayout.MAX_SPACING_M
    if (current != null && current in optionXs.indices && abs(x - optionXs[current]) < spacing * STICKY) return current
    return optionXs.indices.minBy { abs(optionXs[it] - x) }
  }

  companion object {
    /** The fingertip must come from at least this far in front of the plane... */
    const val FRONT_M = 0.005f
    /** ...and reach within this of it (tracking puts the tip joint slightly behind the skin)... */
    const val CONTACT_M = 0.012f
    /** ...without being this far through it (a hand already behind the dock is not a press). */
    const val THROUGH_M = 0.04f
    const val REVEAL_M = 0.03f
    const val DISMISS_M = 0.03f
    const val FLICK_M = 0.04f
    const val RETREAT_M = 0.07f
    const val DOWN_M = 0.05f
    const val LOST_MS = 400L
    const val TIMEOUT_MS = 10_000L
    const val IDLE_MS = 5_000L
    const val MIN_POINT_M = 0.25f
    const val HOVER_M = 0.06f
    const val NEAR_M = 0.15f
    private const val HOVER_HALF_WIDTH_M = 0.07f
    private const val HOVER_HALF_HEIGHT_M = 0.05f
    private const val POINTER_HOVER = 0.6f
    private const val ROW_HALF_HEIGHT_M = 0.03f
    private const val GRAB_HALF_HEIGHT_M = 0.02f
    private const val STICKY = 0.6f
  }
}
