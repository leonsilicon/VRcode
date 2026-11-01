package dev.improve.vrcode

/** What a right-hand index pinch dragged across a terminal does. A pinch released in place clicks either way. */
enum class PinchDrag(val storageValue: String, val label: String, val detail: String) {
  Scroll("scroll", "Scroll", "Drag up or down and the text follows your hand, like a touchscreen"),
  Select("select", "Select text", "Drag across text to highlight it and copy it to the Quest clipboard");

  companion object {
    fun fromStorage(value: String?): PinchDrag = entries.firstOrNull { it.storageValue == value } ?: Scroll
  }
}
