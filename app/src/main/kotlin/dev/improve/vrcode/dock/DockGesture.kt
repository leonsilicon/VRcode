package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.DockShelf
import kotlin.math.abs
import kotlin.math.min

/**
 * Where the raised dock's icons sit: a row in dock-local metres (+X to the user's right), centred
 * on the bar and at most [MAX_SPAN_M] wide. Like the macOS Dock, a separator can split the row: it
 * takes [DockShelf.SEPARATOR] of a slot before the icon at `separatorBefore`. When the row is full,
 * the icons shrink with the spacing instead of overlapping.
 */
internal object DockLayout {
  const val MAX_SPACING_M = 0.06f
  const val MAX_SPAN_M = 0.42f

  fun optionXs(count: Int, separatorBefore: Int? = null): FloatArray {
    if (count <= 0) return FloatArray(0)
    val separated = separated(count, separatorBefore)
    val gap = if (separated) DockShelf.SEPARATOR else 0f
    val spacing = spacing(count, separated)
    val start = -(count - 1 + gap) * spacing / 2f
    return FloatArray(count) { i -> start + (i + if (separated && i >= separatorBefore!!) gap else 0f) * spacing }
  }

  /** The distance between neighbouring icons' centres; every icon size follows it. */
  fun spacing(count: Int, separated: Boolean = false): Float =
    min(MAX_SPACING_M, MAX_SPAN_M / (count.coerceAtLeast(1) + if (separated) DockShelf.SEPARATOR else 0f))

  /** The separator's centre, midway between the icons either side of it, or null without one. */
  fun separatorX(xs: FloatArray, separatorBefore: Int?): Float? =
    separatorBefore?.takeIf { separated(xs.size, it) }?.let { (xs[it - 1] + xs[it]) / 2f }

  /** The spacing of a row from [optionXs]: its closest neighbours, which a separator never splits. */
  fun spacingOf(xs: FloatArray): Float = (1 until xs.size).minOfOrNull { xs[it] - xs[it - 1] } ?: MAX_SPACING_M

  private fun separated(count: Int, before: Int?): Boolean = before != null && before in 1 until count
}

/**
 * A bottom sheet held by its bar: pinch on the visible handle, then swipe up or down. [lift]
 * follows the pinch one-to-one throughout the held gesture, including reversals, and clamps at
 * either end. Releasing settles to the nearest end; a tap or sideways sweep cannot open it.
 * The handle is always the sheet's top edge, so expanded it sits above the options, where it can
 * be grabbed again immediately. Expansion persists until a downward swipe or an option is chosen.
 *
 * Option touches are ignored during a pull, while settling and for [GUARD_MS] after release, so
 * the pulling hand cannot accidentally run an option. Pinches come from tracked hands or a
 * controller's trigger and pointer ray. Coordinates use the collapsed dock's frame: X right,
 * Y up and Z away from the user. [Touch] coordinates are local to the rendered panel's position.
 */
internal class DockGesture {
  /** The panel a touch landed on: the moving handle or the sheet it tops. */
  enum class Surface { Bar, Body }

  enum class Phase { Down, Move, Up }

  /** A touch on [surface] at dock-local ([x], [y]), in metres. */
  data class Touch(val surface: Surface, val phase: Phase, val x: Float, val y: Float)

  sealed interface Event {
    data object None : Event

    data object Revealed : Event

    data class Activated(val index: Int) : Event

    data object Dismissed : Event
  }

  /** A hand's or controller's pointer ray, and whether its trigger (or the SDK's pinch) is held. */
  data class Pointer(val origin: Vector3, val direction: Vector3, val pressed: Boolean = false)

  /**
   * One hand this frame, in scene space: its index [fingertip] and [pinch] point (midway between the
   * thumb and index tips; both null for a controller), its [pointer] ray, and whether its pinch, or a
   * controller's trigger, is [pressed].
   */
  data class Hand(
    val fingertip: Vector3? = null,
    val pinch: Vector3? = null,
    val pointer: Pointer? = null,
    val pressed: Boolean = false,
  )

  var revealed = false
    private set

  /** 0..1: how close a hand about to pinch, or a pointer, is to the bar; 1 while pulling it. */
  var hover = 0f
    private set

  /** 0..1: the sheet's position, including its release animation. */
  var lift = 0f
    private set

  /** The option a fingertip or pointer is near while raised, whose label shows. */
  var highlighted: Int? = null
    private set

  /**
   * Dock-local X (metres) of the fingertip or pointer over the row while an option is highlighted,
   * for the macOS-style magnification that follows the finger between icons; null otherwise.
   */
  var pointedX: Float? = null
    private set

  /** Whether either hand owns a held drag on the handle. */
  val pulling: Boolean get() = pull != null

  val bodyVisible: Boolean get() = lift > 0f

  /** The grab offset is retained, so grabbing near an edge never jumps the handle. */
  private class Pull(val hand: Int, val ray: Boolean, val x: Float, val y: Float, val startLift: Float) {
    var dx = 0f
    var dy = 0f
  }

  private class Settle(val from: Float, val to: Float, val startMs: Long)

  /** A hand in dock-local space; [aim] is where its ray crosses the dock's plane, [aimM] along the ray. */
  private class Local(val tip: Vector3?, val pinch: Vector3?, val aim: Vector3?, val aimM: Float, val pressed: Boolean) {
    /** The ray's crossing, when far enough from the hand to be pointing rather than a typing hand's ray. */
    val pointed: Vector3? get() = aim?.takeIf { aimM >= MIN_POINT_M }
  }

  private var pull: Pull? = null

  private var settle: Settle? = null

  /** Each hand's pinch on the previous frame: only a pinch that closes on the bar grabs it. */
  private val wasPressed = BooleanArray(HANDS) { true }

  private var quietUntilMs = 0L

  /**
   * One frame. [hands] are per hand (0 = left, 1 = right), null when that hand is not tracked.
   * [touches] are the panels' touches since the last frame, in order. [optionXs] comes from
   * [DockLayout].
   */
  fun update(bar: Pose, hands: List<Hand?>, touches: List<Touch>, optionXs: FloatArray, nowMs: Long): Event {
    val inverse = bar.inverse()
    val local = List(HANDS) { i -> hands.getOrNull(i)?.let { localHand(inverse, it) } }
    animateSettle(nowMs)
    var event = pinches(local, nowMs)
    hover = if (pull != null) 1f else hoverAmount(local)
    val pointed = if (revealed && !pulling && settle == null) pointedAt(local, optionXs) else null
    highlighted = pointed?.let { nearest(optionXs, it, highlighted) }
    pointedX = pointed?.takeIf { highlighted != null }
    for (touch in touches) {
      if (touch.phase != Phase.Down) continue
      val result = down(touch, optionXs, nowMs)
      if (event == Event.None) event = result
    }
    return event
  }

  /** Lowers the dock without an event (e.g. VRcode paused); a pinch held meanwhile must close again. */
  fun reset() {
    revealed = false
    highlighted = null
    pointedX = null
    pull = null
    settle = null
    lift = 0f
    quietUntilMs = 0L
    wasPressed.fill(true)
  }

  /** Keeps ownership until release, even at the top, so the same pinch can reverse direction. */
  private fun pinches(hands: List<Local?>, nowMs: Long): Event {
    var event: Event = Event.None
    for (i in 0 until HANDS) {
      val hand = hands[i]
      val pressed = hand?.pressed == true
      val closed = pressed && !wasPressed[i]
      wasPressed[i] = pressed
      val current = pull
      if (current == null) {
        if (closed && hand != null) {
          pull = grab(i, hand)
          if (pull != null) settle = null
        }
        continue
      }
      if (current.hand != i) continue
      if (!pressed) {
        pull = null
        val vertical = abs(current.dy) >= MIN_DRAG_M && abs(current.dy) >= abs(current.dx)
        val expanded = if (vertical) lift >= 0.5f else revealed
        val changed = expanded != revealed
        revealed = expanded
        settleTo(if (expanded) 1f else 0f, nowMs)
        quietUntilMs = nowMs + GUARD_MS
        if (changed) event = if (expanded) Event.Revealed else Event.Dismissed
        continue
      }
      // A missing aim/position while still pressed holds the current position.
      val at = (if (current.ray) hand?.aim else hand?.pinch) ?: continue
      current.dx = at.x - current.x
      current.dy = at.y - current.y
      lift = (current.startLift + current.dy / DockPlacement.TRAVEL_M).coerceIn(0f, 1f)
    }
    return event
  }

  /** A pull for a pinch that just closed: on the bar itself, else along the hand's ray onto it. */
  private fun grab(hand: Int, state: Local): Pull? {
    state.pinch?.takeIf(::inGrabZone)?.let { return Pull(hand, ray = false, it.x, it.y, lift) }
    state.aim?.takeIf(::onBar)?.let { return Pull(hand, ray = true, it.x, it.y, lift) }
    return null
  }

  private fun down(touch: Touch, optionXs: FloatArray, nowMs: Long): Event {
    if (!revealed || pulling || settle != null || nowMs < quietUntilMs) return Event.None
    return when (touch.surface) {
      Surface.Bar -> Event.None
      Surface.Body -> {
        if (!onRow(touch.x, touch.y, optionXs)) return Event.None
        val picked = nearest(optionXs, touch.x, highlighted) ?: return Event.None
        quietUntilMs = nowMs + GUARD_MS
        lower(Event.Activated(picked), nowMs)
      }
    }
  }

  private fun lower(event: Event, nowMs: Long): Event {
    revealed = false
    highlighted = null
    pointedX = null
    settleTo(0f, nowMs)
    return event
  }

  private fun settleTo(target: Float, nowMs: Long) {
    settle = if (lift == target) null else Settle(lift, target, nowMs)
  }

  private fun animateSettle(nowMs: Long) {
    val animation = settle ?: return
    val t = ((nowMs - animation.startMs).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
    val remaining = 1f - t
    lift = animation.to + (animation.from - animation.to) * remaining * remaining * remaining
    if (t >= 1f) settle = null
  }

  private fun localHand(inverse: Pose, hand: Hand): Local {
    fun toLocal(p: Vector3) = inverse.q * p + inverse.t
    var aim: Vector3? = null
    var aimM = 0f
    hand.pointer?.let { pointer ->
      val origin = toLocal(pointer.origin)
      val direction = inverse.q * pointer.direction.normalize()
      if (direction.z > 0.0001f) {
        val t = -origin.z / direction.z
        if (t > 0f) {
          aim = origin + direction * t
          aimM = t
        }
      }
    }
    return Local(hand.fingertip?.let(::toLocal), hand.pinch?.let(::toLocal), aim, aimM, hand.pressed)
  }

  /** Whether a pinch point is close enough in front of the bar to grab it (and to bulge it). */
  private fun inGrabZone(p: Vector3): Boolean =
    abs(p.x) <= GRAB_HALF_WIDTH_M && abs(p.y - DockPlacement.handleY(lift)) <= GRAB_HALF_HEIGHT_M && p.z >= -HOVER_M && p.z <= THROUGH_M

  /** Whether a point on the dock's plane is on the bar's panel. */
  private fun onBar(p: Vector3): Boolean =
    abs(p.x) <= DockPlacement.BAR_HIT_WIDTH_M / 2f && abs(p.y - DockPlacement.handleY(lift)) <= DockPlacement.BAR_HIT_HEIGHT_M / 2f

  /** Whether a dock-local point is on the raised row of options. */
  private fun onRow(x: Float, y: Float, optionXs: FloatArray): Boolean =
    abs(x) <= rowHalfWidth(optionXs) && abs(y - DockPlacement.ROW_Y_M) <= ROW_HALF_HEIGHT_M

  /** Out to the end icons' slots; the icons shrink with the spacing, and so does this. */
  private fun rowHalfWidth(optionXs: FloatArray): Float =
    (optionXs.maxOfOrNull { abs(it) } ?: 0f) + DockLayout.spacingOf(optionXs) / 2f

  /**
   * Dock-local X of the fingertip nearest the row (within [HOVER_M] in front of it), else of a
   * pointer on it, or null when neither is over the row.
   */
  private fun pointedAt(hands: List<Local?>, optionXs: FloatArray): Float? {
    val tip = hands.mapNotNull { it?.tip }
      .filter { it.z >= -HOVER_M && it.z <= THROUGH_M && onRow(it.x, it.y, optionXs) }
      .minByOrNull { abs(it.z) }
    val point = tip ?: hands.firstNotNullOfOrNull { hand -> hand?.pointed?.takeIf { onRow(it.x, it.y, optionXs) } }
    return point?.x
  }

  /** How close a pinch point in front of the bar, or a pointer on it, is: 0 far, 1 touching. */
  private fun hoverAmount(hands: List<Local?>): Float {
    var best = 0f
    for (hand in hands) {
      hand ?: continue
      hand.pinch?.takeIf(::inGrabZone)?.let { best = maxOf(best, (1f - (-it.z).coerceAtLeast(0f) / HOVER_M).coerceIn(0f, 1f)) }
      if (hand.pointed?.let(::onBar) == true) best = maxOf(best, POINTER_HOVER)
    }
    return best
  }

  /** Nearest option to [x]; keeps [current] until [x] is clearly nearer another. */
  private fun nearest(optionXs: FloatArray, x: Float, current: Int?): Int? {
    if (optionXs.isEmpty()) return null
    val spacing = DockLayout.spacingOf(optionXs)
    if (current != null && current in optionXs.indices && abs(x - optionXs[current]) < spacing * STICKY) return current
    return optionXs.indices.minBy { abs(optionXs[it] - x) }
  }

  companion object {
    const val GUARD_MS = 400L
    const val MIN_DRAG_M = 0.015f
    const val SETTLE_MS = 180L
    const val MIN_POINT_M = 0.25f
    const val HOVER_M = 0.06f
    /** A fingertip this far through the plane still counts as at the dock (tracking puts the tip joint behind the skin). */
    const val THROUGH_M = 0.04f
    private const val HANDS = 2
    private const val GRAB_HALF_WIDTH_M = 0.07f
    private const val GRAB_HALF_HEIGHT_M = 0.05f
    private const val POINTER_HOVER = 0.6f
    private const val ROW_HALF_HEIGHT_M = 0.03f
    private const val STICKY = 0.6f
  }
}
