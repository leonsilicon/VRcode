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
 * The dock works like an iPhone's home bar, pulled up with a pinch:
 * 1. Collapsed, only the bar shows. A hand about to pinch it, or a pointer on it, makes it bulge
 *    ([hover]).
 * 2. Pinching the bar and pulling up raises the dock ([Event.Revealed]) once the pinch has moved
 *    [REVEAL_DRAG_M] up from where it closed, mostly upward and while still held; until then the
 *    bar rises with the pull ([lift]). The pinch can close on the bar itself (thumb and index meeting
 *    there) or along a pointer ray aimed at it; a controller's trigger works like a pinch. Touching
 *    or tapping the bar does nothing while the dock is down, so brushing it never raises it, and
 *    neither does a pinch that closed elsewhere and swept onto it. The bar stays where it is,
 *    underneath the raised options.
 * 3. Raised, a fingertip (or pointer) near an option highlights it ([highlighted]) so its label
 *    shows, and touching an option runs it at once ([Event.Activated]); the dock then lowers.
 * 4. Touching the bar again lowers the dock ([Event.Dismissed]), as does leaving it alone for
 *    [IDLE_MS].
 *
 * Touches are ignored while the pinch that raised the dock is still held, and for [GUARD_MS] after
 * it lets go or the dock lowers: the pulling hand ends up among the options, and direct touch can
 * report a finger resting on the bar as a quick release and press, neither of which may run an
 * option or flip the dock straight back. A pinch closing within [GUARD_MS] of the dock lowering does
 * not start a pull either.
 *
 * Touches are the Interaction SDK's own: a fingertip touching a dock panel (direct touch) or a
 * pinching ray on it, delivered to the panel's Compose content and passed in as [Touch]es. The SDK
 * decides contact, so a finger must reach the panel from in front. Only a touch's start counts.
 * Pinches, hover and highlights come from each [Hand]'s tracked joints and pointer ray. All
 * positions are dock-local (see [DockPlacement]): X right, Y up, Z away from the user, the plane at
 * Z = 0.
 */
internal class DockGesture {
  /** The panel a touch landed on: the bar (always shown), or the raised body above it. */
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

  /** 0..1: how far a pinch on the bar has pulled it toward raising the dock. */
  var lift = 0f
    private set

  /** The option a fingertip or pointer is near while raised, whose label shows. */
  var highlighted: Int? = null
    private set

  /** Whether a pinch is holding the bar, before it has pulled far enough to raise the dock. */
  val pulling: Boolean get() = pull != null

  /** A pinch that closed on the bar while the dock was down: its hand, whether along the ray, and where. */
  private class Pull(val hand: Int, val ray: Boolean, val x: Float, val y: Float)

  /** A hand in dock-local space; [aim] is where its ray crosses the dock's plane, [aimM] along the ray. */
  private class Local(val tip: Vector3?, val pinch: Vector3?, val aim: Vector3?, val aimM: Float, val pressed: Boolean) {
    /** The ray's crossing, when far enough from the hand to be pointing rather than a typing hand's ray. */
    val pointed: Vector3? get() = aim?.takeIf { aimM >= MIN_POINT_M }
  }

  private var pull: Pull? = null

  /** The hand whose pinch raised the dock, until it lets go. */
  private var raisedBy: Int? = null

  /** Each hand's pinch on the previous frame: only a pinch that closes on the bar grabs it. */
  private val wasPressed = BooleanArray(HANDS) { true }

  private var lastNearMs = 0L
  private var quietUntilMs = 0L

  /**
   * One frame. [hands] are per hand (0 = left, 1 = right), null when that hand is not tracked.
   * [touches] are the panels' touches since the last frame, in order. [optionXs] comes from
   * [DockLayout].
   */
  fun update(bar: Pose, hands: List<Hand?>, touches: List<Touch>, optionXs: FloatArray, nowMs: Long): Event {
    val inverse = bar.inverse()
    val local = List(HANDS) { i -> hands.getOrNull(i)?.let { localHand(inverse, it) } }
    var event = pinches(local, nowMs)
    hover = if (pull != null) 1f else hoverAmount(local)
    highlighted = if (revealed) pointedOption(local, optionXs) else null
    for (touch in touches) {
      if (touch.phase != Phase.Down) continue
      val result = down(touch, optionXs, nowMs)
      if (event == Event.None) event = result
    }
    if (revealed && event == Event.None) {
      val near = local.any { hand ->
        hand != null && (hand.tip?.let { near(it, optionXs) } == true || hand.pointed?.let { onBody(it, optionXs) } == true)
      }
      if (near || raisedBy != null) lastNearMs = nowMs
      if (nowMs - lastNearMs > IDLE_MS) return lower(Event.Dismissed)
    }
    return event
  }

  /** Lowers the dock without an event (e.g. VRcode paused); a pinch held meanwhile must close again. */
  fun reset() {
    lower(Event.None)
    pull = null
    raisedBy = null
    lift = 0f
    quietUntilMs = 0L
    wasPressed.fill(true)
  }

  /** Starts, follows and ends pulls on the bar; returns [Event.Revealed] when one raises the dock. */
  private fun pinches(hands: List<Local?>, nowMs: Long): Event {
    var event: Event = Event.None
    for (i in 0 until HANDS) {
      val hand = hands[i]
      val pressed = hand?.pressed == true
      val closed = pressed && !wasPressed[i]
      wasPressed[i] = pressed
      if (raisedBy == i && !pressed) {
        raisedBy = null
        quietUntilMs = nowMs + GUARD_MS
      }
      val current = pull
      if (current == null) {
        if (closed && hand != null && !revealed && nowMs >= quietUntilMs) pull = grab(i, hand)
        continue
      }
      if (current.hand != i) continue
      if (!pressed) {
        pull = null
        continue
      }
      // Tracking can drop a frame mid-pull: hold the pull where it was.
      val at = (if (current.ray) hand?.aim else hand?.pinch) ?: continue
      val up = at.y - current.y
      lift = (up / REVEAL_DRAG_M).coerceIn(0f, 1f)
      if (up >= REVEAL_DRAG_M && up >= abs(at.x - current.x)) {
        pull = null
        revealed = true
        raisedBy = i
        lastNearMs = nowMs
        quietUntilMs = nowMs + GUARD_MS
        event = Event.Revealed
      }
    }
    if (pull == null) lift = 0f
    return event
  }

  /** A pull for a pinch that just closed: on the bar itself, else along the hand's ray onto it. */
  private fun grab(hand: Int, state: Local): Pull? {
    state.pinch?.takeIf(::inGrabZone)?.let { return Pull(hand, ray = false, it.x, it.y) }
    state.aim?.takeIf(::onBar)?.let { return Pull(hand, ray = true, it.x, it.y) }
    return null
  }

  private fun down(touch: Touch, optionXs: FloatArray, nowMs: Long): Event {
    lastNearMs = nowMs
    // Down, the bar only rises to a pinch; raised, the pulling hand must let go first.
    if (!revealed || raisedBy != null || nowMs < quietUntilMs) return Event.None
    return when (touch.surface) {
      Surface.Bar -> {
        quietUntilMs = nowMs + GUARD_MS
        lower(Event.Dismissed)
      }
      Surface.Body -> {
        if (!onRow(touch.x, touch.y, optionXs)) return Event.None
        val picked = nearest(optionXs, touch.x, highlighted) ?: return Event.None
        quietUntilMs = nowMs + GUARD_MS
        lower(Event.Activated(picked))
      }
    }
  }

  private fun lower(event: Event): Event {
    revealed = false
    highlighted = null
    return event
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
    abs(p.x) <= GRAB_HALF_WIDTH_M && abs(p.y) <= GRAB_HALF_HEIGHT_M && p.z >= -HOVER_M && p.z <= THROUGH_M

  /** Whether a point on the dock's plane is on the bar's panel. */
  private fun onBar(p: Vector3): Boolean =
    abs(p.x) <= DockPlacement.BAR_HIT_WIDTH_M / 2f && abs(p.y) <= DockPlacement.BAR_HIT_HEIGHT_M / 2f

  /** Whether a dock-local point is on the raised row of options. */
  private fun onRow(x: Float, y: Float, optionXs: FloatArray): Boolean =
    abs(x) <= rowHalfWidth(optionXs) && abs(y - DockPlacement.ROW_Y_M) <= ROW_HALF_HEIGHT_M

  private fun rowHalfWidth(optionXs: FloatArray): Float = (optionXs.lastOrNull() ?: 0f) + DockLayout.MAX_SPACING_M / 2f

  private fun onBody(p: Vector3, optionXs: FloatArray): Boolean =
    abs(p.x) <= rowHalfWidth(optionXs) + 0.02f && p.y >= DockPlacement.BODY_BOTTOM_M - 0.02f && p.y <= DockPlacement.BODY_TOP_M + 0.02f

  private fun near(tip: Vector3, optionXs: FloatArray): Boolean = tip.z >= -NEAR_M && tip.z <= THROUGH_M && onBody(tip, optionXs)

  /**
   * The option under the fingertip nearest the row (within [HOVER_M] in front of it), else under a
   * pointer; keeps the current one until the finger is clearly nearer another.
   */
  private fun pointedOption(hands: List<Local?>, optionXs: FloatArray): Int? {
    val tip = hands.mapNotNull { it?.tip }
      .filter { it.z >= -HOVER_M && it.z <= THROUGH_M && onRow(it.x, it.y, optionXs) }
      .minByOrNull { abs(it.z) }
    val point = tip ?: hands.firstNotNullOfOrNull { hand -> hand?.pointed?.takeIf { onRow(it.x, it.y, optionXs) } } ?: return null
    return nearest(optionXs, point.x, highlighted)
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
    val spacing = if (optionXs.size > 1) optionXs[1] - optionXs[0] else DockLayout.MAX_SPACING_M
    if (current != null && current in optionXs.indices && abs(x - optionXs[current]) < spacing * STICKY) return current
    return optionXs.indices.minBy { abs(optionXs[it] - x) }
  }

  companion object {
    const val IDLE_MS = 5_000L
    const val GUARD_MS = 400L
    /** How far up a pinch on the bar must pull it to raise the dock: past a pinch's own jitter. */
    const val REVEAL_DRAG_M = 0.03f
    const val MIN_POINT_M = 0.25f
    const val HOVER_M = 0.06f
    const val NEAR_M = 0.15f
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
