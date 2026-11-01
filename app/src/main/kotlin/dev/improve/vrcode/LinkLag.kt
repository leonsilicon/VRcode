package dev.improve.vrcode

/**
 * Whether the link to the Mac lags, from xmux's ping round trips (one every [pingIntervalMs]): a
 * slow round trip, or a reply that is overdue, both mean typed keys and terminal output are
 * delayed (a slow Bluetooth link, a backlog of output, a stalling network). Once lag is seen it is
 * held for [holdMs], so a link at the threshold does not flicker the windows' spinner. Pure, for
 * tests; times are in milliseconds from any monotonic clock.
 */
class LinkLag(
  private val pingIntervalMs: Long = 2_000,
  private val slowMs: Long = 300,
  private val holdMs: Long = 1_500,
) {
  private var lastReplyAt: Long? = null
  private var lastRttMs = 0
  private var heldUntil = Long.MIN_VALUE

  /** The link (re)connected at [now]: the next reply is due one ping interval from here. */
  fun start(now: Long) {
    lastReplyAt = now
    lastRttMs = 0
    heldUntil = Long.MIN_VALUE
  }

  fun onReply(rttMs: Int, now: Long) {
    lastReplyAt = now
    lastRttMs = rttMs
  }

  fun lagging(now: Long): Boolean {
    val reply = lastReplyAt ?: return false
    val slow = lastRttMs >= slowMs || now - reply > pingIntervalMs + slowMs
    if (slow) heldUntil = now + holdMs
    return slow || now < heldUntil
  }
}
