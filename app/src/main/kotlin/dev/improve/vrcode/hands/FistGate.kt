package dev.improve.vrcode.hands

/**
 * Debounces the left fist into voice mode on/off. Voice mode ends only when the hand is seen open:
 * silence never ends it, and neither does losing hand tracking (a fist lowered out of the cameras'
 * view, say). While the hand is untracked the gate holds its state and the release timer pauses.
 *
 * Starts after [onMs] of continuous fist; ends after [offMs] of continuous, tracked, open hand.
 */
class FistGate(private val onMs: Long = 120L, private val offMs: Long = 400L) {
  enum class Change { None, Started, Ended }

  /** Whether voice mode is on. */
  var held = false
    private set

  private var candidateSince: Long? = null

  /**
   * Feeds one frame: [tracked] is whether the runtime tracks the left hand as a hand right now,
   * [fist] whether it reports a fist (ignored while untracked). [releaseMs] replaces `offMs` for
   * this frame, e.g. a shorter release while ending voice mode would lose nothing.
   */
  fun update(tracked: Boolean, fist: Boolean, nowMs: Long, releaseMs: Long = offMs): Change {
    if (!tracked) {
      // Unknown pose: keep the current state and forget any half-elapsed transition.
      candidateSince = null
      return Change.None
    }
    if (fist == held) {
      candidateSince = null
      return Change.None
    }
    val since = candidateSince ?: nowMs.also { candidateSince = it }
    if (nowMs - since < if (fist) onMs else releaseMs) return Change.None
    held = fist
    candidateSince = null
    return if (fist) Change.Started else Change.Ended
  }
}
