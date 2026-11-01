package dev.improve.vrcode

import dev.improve.vrcode.pie.PieIcon

/**
 * What the left index-pinch quick inputs ring can send to the focused pane. [key] is the cmux key
 * name and [ctrl] adds Ctrl to it; [Keyboard] has no key and shows the floating keyboard instead.
 * [storageValue] is saved in Settings and synced to VRcode Mac, so never rename one.
 */
enum class QuickInput(
  val storageValue: String,
  val label: String,
  val detail: String,
  val icon: PieIcon,
  val key: String?,
  val ctrl: Boolean = false,
) {
  Escape("escape", "Esc", "Cancel or go back", PieIcon.Escape, "escape"),
  CtrlC("ctrl_c", "Ctrl-C", "Interrupt", PieIcon.Interrupt, "c", ctrl = true),
  Tab("tab", "Tab", "Complete", PieIcon.Tab, "tab"),
  Enter("enter", "Enter", "Submit", PieIcon.Enter, "enter"),
  ShiftTab("shift_tab", "Shift-Tab", "Back a field, switch mode", PieIcon.ShiftTab, "backtab"),
  Backspace("backspace", "Backspace", "Delete back", PieIcon.Backspace, "backspace"),
  Up("up", "Up", "Arrow up, history", PieIcon.ArrowUp, "up"),
  Down("down", "Down", "Arrow down", PieIcon.ArrowDown, "down"),
  Left("left", "Left", "Arrow left", PieIcon.ArrowLeft, "left"),
  Right("right", "Right", "Arrow right", PieIcon.ArrowRight, "right"),
  CtrlD("ctrl_d", "Ctrl-D", "End of input", PieIcon.CtrlD, "d", ctrl = true),
  CtrlZ("ctrl_z", "Ctrl-Z", "Suspend", PieIcon.CtrlZ, "z", ctrl = true),
  CtrlL("ctrl_l", "Ctrl-L", "Clear screen", PieIcon.CtrlL, "l", ctrl = true),
  CtrlR("ctrl_r", "Ctrl-R", "Search history", PieIcon.CtrlR, "r", ctrl = true),
  PageUp("page_up", "Page Up", "Scroll up a page", PieIcon.PageUp, "pageup"),
  PageDown("page_down", "Page Down", "Scroll down a page", PieIcon.PageDown, "pagedown"),
  Home("home", "Home", "Start of line", PieIcon.Home, "home"),
  End("end", "End", "End of line", PieIcon.End, "end"),
  Keyboard("keyboard", "Keyboard", "Show the floating keyboard", PieIcon.Keyboard, null);

  companion object {
    fun fromStorage(value: String?): QuickInput? = entries.firstOrNull { it.storageValue == value }
  }
}

/** The quick inputs ring's eight directions, clockwise from the top, each where it points. */
enum class QuickInputSlot(val label: String, val degrees: Float) {
  Top("Top", 0f), TopRight("Top right", 45f), Right("Right", 90f), BottomRight("Bottom right", 135f),
  Bottom("Bottom", 180f), BottomLeft("Bottom left", 225f), Left("Left", 270f), TopLeft("Top left", 315f),
}

/**
 * What each of the ring's eight directions sends, in [QuickInputSlot] order, or null where it is
 * empty. Empty directions draw nothing and take no part in picking: each placed input's pick area
 * reaches halfway to its neighbours. Each input appears at most once.
 */
data class QuickInputRing(val slots: List<QuickInput?>) {
  init {
    require(slots.size == QuickInputSlot.entries.size) { "the ring has ${QuickInputSlot.entries.size} directions" }
  }

  operator fun get(slot: QuickInputSlot): QuickInput? = slots[slot.ordinal]

  /** The filled directions, clockwise from the top. */
  val placed: List<Pair<QuickInputSlot, QuickInput>>
    get() = QuickInputSlot.entries.mapNotNull { slot -> this[slot]?.let { slot to it } }

  /** Where [input] sits, if it is on the ring. */
  fun slotOf(input: QuickInput): QuickInputSlot? = QuickInputSlot.entries.firstOrNull { this[it] == input }

  /**
   * Puts [input] at [slot], or empties it when null. An input already elsewhere on the ring swaps
   * places with whatever was at [slot], so moving keys around never loses one.
   */
  fun with(slot: QuickInputSlot, input: QuickInput?): QuickInputRing {
    val next = slots.toMutableList()
    val from = input?.let(::slotOf)
    if (from != null) next[from.ordinal] = this[slot]
    next[slot.ordinal] = input
    return QuickInputRing(next)
  }

  /** Drops repeats of an input, keeping its first direction clockwise from the top. */
  fun sanitized(): QuickInputRing {
    val seen = mutableSetOf<QuickInput>()
    return QuickInputRing(slots.map { input -> input?.takeIf { seen.add(it) } })
  }

  /** One entry per direction, `""` where empty (SharedPreferences and VRcode Mac). */
  val storageValues: List<String> get() = slots.map { it?.storageValue.orEmpty() }

  val storageValue: String get() = storageValues.joinToString(",")

  override fun toString(): String =
    placed.joinToString(prefix = "[", postfix = "]") { (slot, input) -> "${slot.label}=${input.label}" }

  companion object {
    // Declared before Default, which is built from it: companion properties initialize in order.
    val Empty = QuickInputRing(List(QuickInputSlot.entries.size) { null })

    /** Esc, Ctrl-C, Tab and Enter on the diagonals, laid out like a keypad's corners. */
    val Default = ring(
      QuickInputSlot.TopLeft to QuickInput.Escape,
      QuickInputSlot.TopRight to QuickInput.CtrlC,
      QuickInputSlot.BottomLeft to QuickInput.Tab,
      QuickInputSlot.BottomRight to QuickInput.Enter,
    )

    fun ring(vararg placed: Pair<QuickInputSlot, QuickInput>): QuickInputRing =
      placed.fold(Empty) { ring, (slot, input) -> ring.with(slot, input) }

    /**
     * Reads [storageValues] or [fromStorage]'s values. Anything but eight entries (a missing or
     * damaged preference) gives [Default]; unknown inputs leave their direction empty.
     */
    fun fromStorageValues(values: List<String?>?): QuickInputRing =
      if (values?.size != QuickInputSlot.entries.size) Default
      else QuickInputRing(values.map(QuickInput::fromStorage)).sanitized()

    fun fromStorage(value: String?): QuickInputRing = fromStorageValues(value?.split(','))
  }
}
