package dev.improve.vrcode

import androidx.compose.ui.geometry.Offset
import dev.improve.xmux.client.ScreenId

/**
 * Which title-bar drag events may move a window: only those of a pinch or trigger that is held now
 * and that started while its hand pointed at that window's title bar. Releasing it ends the drag for
 * good, even if the header's gesture carries on.
 *
 * The header's own drag gesture cannot decide this. It follows the panel's pointer events, and the
 * Interaction SDK turns its count of a hand's selects into a held trigger for every panel the ray
 * crosses (`IsdkSystem.emitInputEvents`, SDK 0.14). With that count left above zero, a ray moving
 * onto a title bar without any pinch arrives as a press and starts a drag. A press that began
 * elsewhere and swept onto a title bar arrives the same way. So a press here is the hand's own
 * pinch or trigger bit, read each frame, tied to the title bar its ray was on when it went down.
 *
 * When a real press takes over a gesture the header began without one, the window is grabbed where
 * the pointer is then, not at the earlier phantom grab point, so it does not jump.
 */
internal class TitleBarDragGate(private val log: (String) -> Unit = {}) {
  /** One hand: whether its pinch or trigger is held, and the window whose title bar its ray is on (or null). */
  class HandState(val left: Boolean, val pressed: Boolean, val header: ScreenId?)

  /** One held press and the title bar it started on (null: it started elsewhere). */
  private class Press(val header: ScreenId?)

  /** A window's drag: the header gesture's grab point [anchor], the press holding it, and where it is grabbed. */
  private class Hold(val anchor: Offset, val left: Boolean, val press: Press, val grab: Offset)

  private val presses = HashMap<Boolean, Press>()
  private val holds = HashMap<ScreenId, Hold>()

  /** Each window's last header gesture that moved nothing, so a press that takes it over is grabbed where it points. */
  private val refused = HashMap<ScreenId, Offset>()

  /** The hand holding [screen]'s drag (true for the left), or null. */
  fun holder(screen: ScreenId): Boolean? = holds[screen]?.left

  /** Every tracked hand, each frame and before each drag event. A hand that is missing has let go. */
  fun update(hands: List<HandState>) {
    for (left in SIDES) {
      val hand = hands.firstOrNull { it.left == left }
      if (hand == null || !hand.pressed) presses.remove(left)
      else if (left !in presses) presses[left] = Press(hand.header)
    }
    val released = holds.filterValues { presses[it.left] !== it.press }
    for ((screen, hold) in released) {
      holds.remove(screen)
      refused[screen] = hold.anchor
      log("title-bar drag: screen ${screen.value} released by the ${side(hold.left)} hand")
    }
  }

  /**
   * A header drag event: the gesture grabbed [screen] at [anchor] and its pointer is at [position]
   * (panel pixels). Returns the grab point to move the window by, or null to leave it in place.
   */
  fun drag(screen: ScreenId, anchor: Offset, position: Offset): Offset? {
    holds[screen]?.let { hold ->
      if (hold.anchor == anchor) return hold.grab
      // The header started another gesture: the earlier one is over.
      holds.remove(screen)
    }
    val left = SIDES.firstOrNull { presses[it]?.header == screen }
    if (left == null) {
      if (refused[screen] != anchor) {
        val held = SIDES.filter { it in presses }.joinToString { side(it) }.ifEmpty { "none" }
        log("title-bar drag: screen ${screen.value} ignored, no pinch or trigger started on its title bar (held: $held)")
      }
      refused[screen] = anchor
      return null
    }
    val takeover = refused.remove(screen) == anchor
    val grab = if (takeover) position else anchor
    holds[screen] = Hold(anchor, left, presses.getValue(left), grab)
    log("title-bar drag: screen ${screen.value} held by the ${side(left)} hand" + if (takeover) ", grabbed where it points" else "")
    return grab
  }

  /** Ends every drag (a disconnect); the hands' presses stay as they are. */
  fun cancel() {
    holds.clear()
    refused.clear()
  }

  private companion object {
    val SIDES = listOf(false, true)
    fun side(left: Boolean) = if (left) "left" else "right"
  }
}
