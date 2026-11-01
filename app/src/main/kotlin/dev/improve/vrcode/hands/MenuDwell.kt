package dev.improve.vrcode.hands

/** A continuous hover while pinching fires once, after one second. */
class MenuDwell(private val durationMs: Long = DURATION_MS) {
  init { require(durationMs > 0L) }

  var hovered: Int? = null
    private set
  var progress = 0f
    private set
  private var lastMs = 0L
  private var elapsedMs = 0L
  private var wasConfirmed = false
  private var fired = false

  /**
   * Null means off the items. Brief missing tracking/pinch samples pause the clock; they cannot
   * finish a dwell. A real release or cancellation must call [reset].
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
