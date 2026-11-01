package dev.improve.vrcode

/**
 * The left controller's menu button toggles Settings once per press. The SDK button and the
 * Android menu key can both describe the same press, so toggles closer than [DEBOUNCE_MS] are one.
 * With hand tracking the same press is the left palm pinch, which opens the quick inputs instead.
 */
class MenuButtonGate {
  private var wasDown = false
  private var lastToggleMs: Long? = null

  fun update(menuDown: Boolean, nowMs: Long): Boolean {
    val pressed = menuDown && !wasDown
    wasDown = menuDown
    return pressed && request(nowMs)
  }

  fun request(nowMs: Long): Boolean {
    val last = lastToggleMs
    if (last != null && nowMs - last < DEBOUNCE_MS) return false
    lastToggleMs = nowMs
    return true
  }

  private companion object {
    const val DEBOUNCE_MS = 350L
  }
}
