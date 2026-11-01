package dev.improve.vrcode

/** Retries until passthrough is active, and reasserts suppression after system interruptions. */
internal class BoundaryRequest {
  private var session = 0L
  private var suppress: Boolean? = null
  private var nextRequestMs = 0L
  private var accepted = false
  private var unsupported = false

  fun update(session: Long, suppress: Boolean, nowMs: Long, request: (Boolean) -> Int): Int? {
    if (session == 0L) {
      reset()
      return null
    }
    if (this.session != session) reset()
    if (this.suppress != suppress) {
      this.suppress = suppress
      accepted = false
      nextRequestMs = 0L // Restoring the boundary must bypass any suppression retry delay.
    }
    this.session = session
    if (unsupported || nowMs < nextRequestMs || (accepted && !suppress)) return null
    val result = request(suppress)
    // A positive XR_BOUNDARY_VISIBILITY_SUPPRESSION_NOT_ALLOWED_META is NOT acceptance.
    accepted = result == 0
    unsupported = result == -7 || result == -8 || result == -9
    nextRequestMs = nowMs + if (accepted) 1000L else 500L
    return result
  }

  fun reset() {
    session = 0L
    suppress = null
    nextRequestMs = 0L
    accepted = false
    unsupported = false
  }
}
