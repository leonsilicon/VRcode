package dev.improve.vrcode.keyboard

/**
 * Whether Meta's keyboard really came up after [KeyboardFeature] asked for it.
 *
 * Android accepting `showSoftInput` means little on the Quest: the keyboard can still decline to
 * show for a panel (logcat: `ImeTracker … onFailed at PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE`), and
 * nothing tells the app. So the window's IME insets have to report it showing within [timeoutMs]
 * of [askedAtMs], or the request counts as failed.
 */
class SystemKeyboardCheck(private val askedAtMs: Long, private val timeoutMs: Long = TIMEOUT_MS) {
  enum class State {
    /** Asked for, not showing yet. */
    Waiting,
    /** The first frame it shows. */
    Appeared,
    Showing,
    /** It showed and has gone away (its own close button, or the system took it down). */
    Closed,
    /** It never showed within the timeout. */
    Failed,
  }

  private var seen = false

  fun update(imeVisible: Boolean, nowMs: Long): State =
    when {
      imeVisible -> if (seen) State.Showing else State.Appeared.also { seen = true }
      seen -> State.Closed
      nowMs - askedAtMs >= timeoutMs -> State.Failed
      else -> State.Waiting
    }

  companion object {
    /** Long enough for the IME's show to start; the floating keyboard waits this long once a session. */
    const val TIMEOUT_MS = 1_000L
  }
}
