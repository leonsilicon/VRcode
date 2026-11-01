package dev.improve.vrcode.hands

/** One click per runtime pinch, requiring a tracked release after startup or any cancellation. */
class MiddlePinchGate {
  enum class Signal { Released, Pinched, Blocked }

  private var armed = false

  /** Null (tracking lost) and [Signal.Blocked] cancel until a fresh release. */
  fun update(signal: Signal?): Boolean {
    val click = armed && signal == Signal.Pinched
    armed = signal == Signal.Released
    return click
  }

  fun cancel() {
    armed = false
  }
}
