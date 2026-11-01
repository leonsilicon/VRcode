package dev.improve.vrcode.hands

/**
 * Hovering a palm menu item's icon while pinching runs it once, after one second ([progress] fills
 * its label pill meanwhile); moving on out to its label pill runs it at once ([finish]). Reaching an
 * item's icon runs nothing by itself, and releasing never runs anything.
 */
class MenuDwell(private val durationMs: Long = DURATION_MS) {
  init { require(durationMs > 0L) }

  var hovered: Int? = null
    private set
  /** 0 to 1: how much of the second the hover has lasted. The item runs at 1. */
  var progress = 0f
    private set
  private var lastMs = 0L
  private var elapsedMs = 0L
  private var wasConfirmed = false
  private var fired = false

  /**
   * [item] is null off the items. Returns the item once its second is up, else null.
   *
   * Brief missing tracking/pinch samples ([confirmed] false) pause the clock; they cannot finish a
   * dwell. A real release or cancellation must call [reset].
   */
  fun update(item: Int?, nowMs: Long, confirmed: Boolean = true): Int? {
    if (fired) return null
    val delta = if (confirmed && wasConfirmed) (nowMs - lastMs).coerceAtLeast(0L) else 0L
    lastMs = nowMs
    wasConfirmed = confirmed
    if (!confirmed) return null
    if (item == null || item != hovered) {
      hovered = item
      elapsedMs = 0L
      progress = 0f
      return null
    }
    elapsedMs += delta
    progress = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    if (progress < 1f) return null
    fired = true
    return item
  }

  /**
   * The hand or ray reached the hovered item's label pill: runs [item] now, once. Only the item
   * already hovered (whose label is showing) can finish, so crossing straight into a label's spot from
   * elsewhere does not.
   */
  fun finish(item: Int): Int? {
    if (fired || item != hovered) return null
    fired = true
    progress = 1f
    return item
  }

  fun reset() {
    hovered = null
    progress = 0f
    lastMs = 0L
    elapsedMs = 0L
    wasConfirmed = false
    fired = false
  }

  companion object {
    const val DURATION_MS = 1000L
  }
}
