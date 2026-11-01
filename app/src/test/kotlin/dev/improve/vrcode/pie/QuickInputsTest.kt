package dev.improve.vrcode.pie

import dev.improve.vrcode.QuickInput
import dev.improve.vrcode.QuickInputRing
import dev.improve.vrcode.QuickInputSlot
import dev.improve.vrcode.hands.RadialMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickInputsTest {
  /** The menu the left index-pinch ring builds for [ring]: its placed inputs at their directions. */
  private fun menu(ring: QuickInputRing) =
    RadialMenu(ring.placed.size, degrees = ring.placed.map { (slot, _) -> slot.degrees })

  /** The input the hand picks on [ring] by moving [rightM] right and [upM] up from the pinch point. */
  private fun QuickInputRing.at(rightM: Float, upM: Float): QuickInput? = menu(this).pick(rightM, upM)?.let { placed[it].second }

  @Test fun theDefaultKeysSitOnTheDiagonalsAndShareTheRing() {
    val ring = QuickInputRing.Default
    assertEquals(QuickInput.Escape, ring.at(-0.06f, 0.06f)) // top left
    assertEquals(QuickInput.CtrlC, ring.at(0.06f, 0.06f)) // top right
    assertEquals(QuickInput.Tab, ring.at(-0.06f, -0.06f)) // bottom left
    assertEquals(QuickInput.Enter, ring.at(0.06f, -0.06f)) // bottom right
    // The empty directions belong to the nearest key: each key's area is a quarter of the ring.
    assertEquals(QuickInput.CtrlC, ring.at(0.08f, 0.01f)) // right, a little up
    assertEquals(QuickInput.Enter, ring.at(0.08f, -0.01f)) // right, a little down
    assertEquals(QuickInput.Escape, ring.at(-0.01f, 0.08f)) // up, a little left
    assertEquals(QuickInput.Tab, ring.at(-0.01f, -0.08f)) // down, a little left
    assertNull(ring.at(0.01f, 0.01f)) // still in the dead zone
  }

  @Test fun aFullRingKeepsTheEightWayKeypad() {
    val full = QuickInputRing(listOf(QuickInput.Up, QuickInput.CtrlC, QuickInput.Right, QuickInput.Enter,
      QuickInput.Down, QuickInput.Tab, QuickInput.Left, QuickInput.Escape))
    assertEquals(QuickInput.Up, full.at(0f, 0.08f))
    assertEquals(QuickInput.CtrlC, full.at(0.06f, 0.06f))
    assertEquals(QuickInput.Right, full.at(0.08f, 0f))
    assertEquals(QuickInput.Enter, full.at(0.06f, -0.06f))
    assertEquals(QuickInput.Down, full.at(0f, -0.08f))
    assertEquals(QuickInput.Tab, full.at(-0.06f, -0.06f))
    assertEquals(QuickInput.Left, full.at(-0.08f, 0f))
    assertEquals(QuickInput.Escape, full.at(-0.06f, 0.06f))
    // Given directions agree with the evenly spaced ring they match.
    val even = RadialMenu(8)
    val given = menu(full)
    for (deg in 0 until 360 step 7) {
      val a = Math.toRadians(deg + 1.0) // never exactly on a border (22.5° + 45°k)
      val r = (0.08 * kotlin.math.sin(a)).toFloat()
      val u = (0.08 * kotlin.math.cos(a)).toFloat()
      assertEquals("at $deg°", even.pick(r, u), given.pick(r, u))
      for (item in 0 until 8) assertEquals(even.along(item, r, u), given.along(item, r, u), 1e-6f)
    }
  }

  @Test fun aSingleKeyTakesTheWholeRingAndSwipingOutAlongItsSpokeCounts() {
    val ring = QuickInputRing.ring(QuickInputSlot.Right to QuickInput.CtrlC)
    for ((r, u) in listOf(0.08f to 0f, -0.08f to 0f, 0f to 0.08f)) assertEquals(QuickInput.CtrlC, ring.at(r, u))
    assertEquals(0.08f, menu(ring).along(0, 0.08f, 0f), 1e-6f)
    assertEquals(-0.08f, menu(ring).along(0, -0.08f, 0f), 1e-6f)
  }

  @Test fun keysSendCmuxKeyNamesAndKeyboardShowsTheKeyboard() {
    assertEquals("escape", QuickInput.Escape.key)
    assertEquals("backtab", QuickInput.ShiftTab.key)
    assertEquals("pageup", QuickInput.PageUp.key)
    assertNull(QuickInput.Keyboard.key)
    assertEquals(setOf(QuickInput.CtrlC, QuickInput.CtrlD, QuickInput.CtrlZ, QuickInput.CtrlL, QuickInput.CtrlR),
      QuickInput.entries.filter { it.ctrl }.toSet())
    for (input in QuickInput.entries.filter { it.ctrl }) assertEquals(1, input.key!!.length)
  }
}
