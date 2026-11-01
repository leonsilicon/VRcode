package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.math.min

/**
 * Where the dock's options sit while it is pressed: a row along the line in dock-local metres
 * (+X to the user's right), centred on the line and at most [MAX_SPAN_M] wide.
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
 * Press, slide, flick: the dock is used by touching its line with a fingertip (or pinching a
 * pointer ray on it from a distance), sliding sideways to an option, and flicking up from it to
 * activate it. Letting go without a flick (pulling the finger back toward the face, moving down,
 * or releasing the pinch) cancels.
 *
 * A touch press needs the fingertip to reach the line's plane from in front of it, within the
 * line's footprint, so hands typing below the dock, or a finger behind it, never press it. All
 * positions are in the dock's local frame: X right, Y up, Z away from the user (the panel plane is
 * Z = 0).
 */
internal class DockGesture {
  enum class Source { Touch, Ray }

  sealed interface Event {
    data object None : Event

    data object Pressed : Event

    data class Activated(val index: Int) : Event

    data object Cancelled : Event
  }

  /** A pointer ray and whether its hand is pinching. */
  data class Pointer(val origin: Vector3, val direction: Vector3, val pinched: Boolean)

  var pressed = false
    private set

  var source: Source? = null
    private set

  /** Index into the per-hand input lists (0 = left, 1 = right) of the hand that pressed. */
  var hand = -1
    private set

  /** Local X where the press landed and where the finger (or ray) is now. */
  var pressX = 0f
    private set

  var fingerX = 0f
    private set

  /** 0 at the press height, 1 at a full flick. */
  var lift = 0f
    private set

  var highlighted: Int? = null
    private set

  private var pressY = 0f
  private var pressedAtMs = 0L
  private var lostSinceMs: Long? = null
  private val previousZ = arrayOfNulls<Float>(2)
  private val previousPinch = BooleanArray(2)

  /**
   * One frame. [fingertips] and [pointers] are per hand (0 = left, 1 = right), null when that hand
   * is not tracked; [optionXs] comes from [DockLayout].
   */
  fun update(dock: Pose, fingertips: List<Vector3?>, pointers: List<Pointer?>, optionXs: FloatArray, nowMs: Long): Event {
    val inverse = dock.inverse()
    val tips = List(2) { i -> fingertips.getOrNull(i)?.let { inverse.q * it + inverse.t } }
    val event = if (pressed) follow(inverse, tips, pointers, optionXs, nowMs) else detectPress(dock, tips, pointers, optionXs, nowMs)
    for (i in 0..1) {
      previousZ[i] = tips[i]?.z
      previousPinch[i] = pointers.getOrNull(i)?.pinched == true
    }
    return event
  }

  fun cancel() {
    pressed = false
    source = null
    hand = -1
    highlighted = null
    lift = 0f
    lostSinceMs = null
  }

  private fun detectPress(dock: Pose, tips: List<Vector3?>, pointers: List<Pointer?>, optionXs: FloatArray, nowMs: Long): Event {
    for (i in 0..1) {
      val tip = tips[i] ?: continue
      val before = previousZ[i] ?: continue
      val onLine = abs(tip.x) <= LINE_HIT_HALF_WIDTH_M && abs(tip.y) <= LINE_HIT_HALF_HEIGHT_M
      if (onLine && before < -FRONT_M && tip.z >= -CONTACT_M && tip.z <= THROUGH_M) return press(Source.Touch, i, tip.x, tip.y, optionXs, nowMs)
    }
    for (i in 0..1) {
      val pointer = pointers.getOrNull(i) ?: continue
      if (!pointer.pinched || previousPinch[i]) continue
      val distance = DockPlacement.hitDistance(dock, pointer.origin, pointer.direction, LINE_HIT_HALF_WIDTH_M * 2f, LINE_HIT_HALF_HEIGHT_M * 2f) ?: continue
      // Pointing at something within arm's reach from a few centimetres is a typing hand's ray, not a press.
      if (distance < MIN_POINT_M) continue
      val hit = planeHit(dock.inverse(), pointer) ?: continue
      return press(Source.Ray, i, hit.x, hit.y, optionXs, nowMs)
    }
    return Event.None
  }

  private fun press(from: Source, index: Int, x: Float, y: Float, optionXs: FloatArray, nowMs: Long): Event {
    pressed = true
    source = from
    hand = index
    pressX = x
    pressY = y
    fingerX = x
    lift = 0f
    pressedAtMs = nowMs
    lostSinceMs = null
    highlighted = nearest(optionXs, x, null)
    return Event.Pressed
  }

  private fun follow(inverse: Pose, tips: List<Vector3?>, pointers: List<Pointer?>, optionXs: FloatArray, nowMs: Long): Event {
    val point: Vector3? =
      when (source) {
        Source.Touch -> tips[hand]
        Source.Ray -> pointers.getOrNull(hand)?.let { pointer -> if (!pointer.pinched) return finish(Event.Cancelled) else planeHit(inverse, pointer) }
        null -> null
      }
    if (point == null) {
      val since = lostSinceMs ?: nowMs.also { lostSinceMs = it }
      return if (nowMs - since > LOST_MS) finish(Event.Cancelled) else Event.None
    }
    lostSinceMs = null
    fingerX = point.x
    val rise = point.y - pressY
    lift = (rise / FLICK_M).coerceIn(0f, 1f)
    highlighted = nearest(optionXs, point.x, highlighted)
    val picked = highlighted
    if (rise >= FLICK_M && picked != null) return finish(Event.Activated(picked))
    val retreated = source == Source.Touch && point.z < -RETREAT_M
    if (retreated || rise < -DOWN_M || nowMs - pressedAtMs > TIMEOUT_MS) return finish(Event.Cancelled)
    return Event.None
  }

  private fun finish(event: Event): Event {
    cancel()
    return event
  }

  /** Nearest option to [x]; keeps [current] until the finger is clearly nearer another. */
  private fun nearest(optionXs: FloatArray, x: Float, current: Int?): Int? {
    if (optionXs.isEmpty()) return null
    val spacing = if (optionXs.size > 1) optionXs[1] - optionXs[0] else DockLayout.MAX_SPACING_M
    if (current != null && current in optionXs.indices && abs(x - optionXs[current]) < spacing * STICKY) return current
    return optionXs.indices.minBy { abs(optionXs[it] - x) }
  }

  /** Where a ray crosses the dock's plane, in dock-local coordinates, ignoring the footprint. */
  private fun planeHit(inverse: Pose, pointer: Pointer): Vector3? {
    val origin = inverse.q * pointer.origin + inverse.t
    val direction = inverse.q * pointer.direction.normalize()
    if (direction.z <= 0.0001f) return null
    val t = -origin.z / direction.z
    if (t < 0f) return null
    return origin + direction * t
  }

  companion object {
    /** The line's touch target: wider and taller than the drawn line, so it is easy to hit. */
    const val LINE_HIT_HALF_WIDTH_M = 0.05f
    const val LINE_HIT_HALF_HEIGHT_M = 0.025f
    /** The fingertip must come from at least this far in front of the plane... */
    const val FRONT_M = 0.005f
    /** ...and reach within this of it (tracking puts the tip joint slightly behind the skin)... */
    const val CONTACT_M = 0.012f
    /** ...without being this far through it (a hand already behind the dock is not a press). */
    const val THROUGH_M = 0.04f
    const val FLICK_M = 0.04f
    const val RETREAT_M = 0.07f
    const val DOWN_M = 0.05f
    const val LOST_MS = 400L
    const val TIMEOUT_MS = 10_000L
    const val MIN_POINT_M = 0.25f
    private const val STICKY = 0.6f
  }
}
