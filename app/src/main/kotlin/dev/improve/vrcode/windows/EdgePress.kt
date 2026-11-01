package dev.improve.vrcode.windows

import com.meta.spatial.runtime.ButtonBits
import dev.improve.vrcode.hands.HandAim
import dev.improve.vrcode.hands.IndexPinch

/**
 * A hand's own index pinch, independent of whether ISDK is selecting a panel. ISDK's synthetic
 * trigger events only exist on interactables; the resize bands are outside them. Controllers
 * keep using the trigger or A/X, like the panels. Null means lost tracking, not an open hand.
 */
internal class EdgePress(private val left: Boolean) {
  private var pinching = false

  fun update(isHand: Boolean, buttons: Int, frame: HandAim.Frame?): Boolean? {
    if (!isHand) {
      pinching = false
      val select = if (left) ButtonBits.ButtonTriggerL or ButtonBits.ButtonX
        else ButtonBits.ButtonTriggerR or ButtonBits.ButtonA
      return buttons and select != 0
    }
    if (frame == null || frame.has(HandAim.SYSTEM_GESTURE)) {
      reset()
      return null
    }
    pinching = IndexPinch.down(frame, pinching)
    return pinching
  }

  fun reset() { pinching = false }
}
