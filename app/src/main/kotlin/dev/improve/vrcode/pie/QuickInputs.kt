package dev.improve.vrcode.pie

/**
 * The left index-pinch ring's items, each where it points: arrows at top, right, bottom and left;
 * Esc top-left, Keyboard top-right, Tab bottom-left, Enter bottom-right. Item 0 is at the top and
 * the rest go clockwise, as in [dev.improve.vrcode.hands.RadialMenu].
 */
object QuickInputs {
  /** One item; [key] is the cmux key name sent to the focused pane, or null to show the keyboard. */
  class Entry(val label: String, val icon: PieIcon, val key: String?)

  val entries = listOf(
    Entry("Up", PieIcon.ArrowUp, "up"),
    Entry("Keyboard", PieIcon.Keyboard, null),
    Entry("Right", PieIcon.ArrowRight, "right"),
    Entry("Enter", PieIcon.Enter, "enter"),
    Entry("Down", PieIcon.ArrowDown, "down"),
    Entry("Tab", PieIcon.Tab, "tab"),
    Entry("Left", PieIcon.ArrowLeft, "left"),
    Entry("Esc", PieIcon.Escape, "escape"),
  )
}
