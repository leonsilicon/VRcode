package dev.improve.vrcode

/** Both the SDK menu button and native palm menu bit can describe the same press. */
class MenuButtonGate {
  private var wasDown = false
  private var lastToggleMs: Long? = null

  fun update(sdkMenuDown: Boolean, leftPalmMenuDown: Boolean, nowMs: Long): Boolean {
    val down = sdkMenuDown || leftPalmMenuDown
    val pressed = down && !wasDown
    wasDown = down
    return pressed && request(nowMs)
  }

  fun request(nowMs: Long): Boolean {
    val last = lastToggleMs
    if (last != null && nowMs - last < 350L) return false
    lastToggleMs = nowMs
    return true
  }
}

