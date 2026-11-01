package dev.improve.vrcode.hands

/**
 * Hovering a palm menu item while pinching runs it once: after one second, or sooner by swiping on
 * outward along the item to the far end of its pill, which speeds up the same clock. Reaching an
 * item runs nothing by itself, and releasing never runs anything.
 */
class MenuDwell(private val durationMs: Long = DURATION_MS) {
  init { require(durationMs > 0L) }

  var hovered: Int? = null
    private set
  /** 0 to 1: the hover's time plus its swipe. The item runs at 1. */
  var progress = 0f
    private set
  /** How much of [progress] the swipe contributes, for the log. */
  var swiped = 0f
    private set
  private var lastMs = 0L
  private var elapsedMs = 0L
  /** The innermost point of this hover along the item, in swipe lengths; the swipe counts from here. */
  private var swipeFrom: Float? = null
  private var wasConfirmed = false
  private var fired = false

  /**
   * [item] is null off the items. [along] is how far the hand is along [item]'s direction, in units
   * of that item's whole swipe, or null when the pinching hand is not what hovers it (the other
   * hand's ray). Moving outward from the innermost point of the hover adds that fraction of the
   * second, so a whole swipe runs the item at once; moving back takes it away again.
   *
   * Brief missing tracking/pinch samples ([confirmed] false) pause both the clock and the swipe; they
   * cannot finish a dwell. A real release or cancellation must call [reset].
   */
  fun update(item: Int?, nowMs: Long, confirmed: Boolean = true, along: Float? = null): Int? {
    if (fired) return null
    val delta = if (confirmed && wasConfirmed) (nowMs - lastMs).coerceAtLeast(0L) else 0L
    lastMs = nowMs
    wasConfirmed = confirmed
    if (!confirmed) return null
    if (item == null || item != hovered) {
      hovered = item
      elapsedMs = 0L
      swipeFrom = along
      swiped = 0f
      progress = 0f
      return null
    }
    elapsedMs += delta
    // A new item starts its swipe where the hand is, so crossing into it far out cannot run it.
    val from = if (along == null) null else minOf(swipeFrom ?: along, along)
    swipeFrom = from
    swiped = if (along == null || from == null) 0f else (along - from).coerceIn(0f, 1f)
    progress = (elapsedMs.toFloat() / durationMs + swiped).coerceIn(0f, 1f)
    if (progress < 1f) return null
    fired = true
    return item
  }

  fun reset() {
    hovered = null
    progress = 0f
    swiped = 0f
    lastMs = 0L
    elapsedMs = 0L
    swipeFrom = null
    wasConfirmed = false
    fired = false
  }

  companion object {
    const val DURATION_MS = 1000L
  }
}
