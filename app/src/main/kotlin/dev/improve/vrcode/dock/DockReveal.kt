package dev.improve.vrcode.dock

/**
 * The dock stays collapsed to a small handle until a pointer or hand reaches it, and stays open
 * for [holdMs] after the last contact so moving between buttons never collapses it.
 */
internal class DockReveal(private val holdMs: Long = 1_200L) {
  private var lastReachMs: Long? = null
  var expanded = false
    private set

  fun update(reached: Boolean, nowMs: Long): Boolean {
    if (reached) lastReachMs = nowMs
    expanded = lastReachMs?.let { nowMs - it in 0..holdMs } == true
    return expanded
  }

  /** A direct press on the handle opens it even without pointer tracking. */
  fun reveal(nowMs: Long) {
    lastReachMs = nowMs
    expanded = true
  }

  fun collapse() {
    lastReachMs = null
    expanded = false
  }
}
