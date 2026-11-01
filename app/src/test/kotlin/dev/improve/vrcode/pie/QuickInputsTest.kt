package dev.improve.vrcode.pie

import dev.improve.vrcode.hands.RadialMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickInputsTest {
  private val menu = RadialMenu(QuickInputs.entries.size)

  /** The entry the hand picks by moving [rightM] right and [upM] up from the pinch point. */
  private fun at(rightM: Float, upM: Float) = QuickInputs.entries[menu.pick(rightM, upM)!!]

  @Test
  fun eachKeySitsWhereItPoints() {
    assertEquals("Up", at(0f, 0.08f).label)
    assertEquals("Keyboard", at(0.06f, 0.06f).label) // top right
    assertEquals("Right", at(0.08f, 0f).label)
    assertEquals("Enter", at(0.06f, -0.06f).label) // bottom right
    assertEquals("Down", at(0f, -0.08f).label)
    assertEquals("Tab", at(-0.06f, -0.06f).label) // bottom left
    assertEquals("Left", at(-0.08f, 0f).label)
    assertEquals("Esc", at(-0.06f, 0.06f).label) // top left
  }

  @Test
  fun itemsSendTheirKeysAndKeyboardShowsTheKeyboard() {
    val keys = QuickInputs.entries.associate { it.label to it.key }
    assertEquals(
      mapOf("Up" to "up", "Right" to "right", "Enter" to "enter", "Down" to "down", "Tab" to "tab",
        "Left" to "left", "Esc" to "escape"),
      keys.filterValues { it != null },
    )
    assertNull(keys.getValue("Keyboard"))
  }
}
