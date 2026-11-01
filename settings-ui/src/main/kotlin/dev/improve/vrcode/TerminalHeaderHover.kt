package dev.improve.vrcode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** A Quest ray can hover a panel without delivering an Android mouse-hover event. */
class TerminalHeaderHover {
  private val bounds = mutableMapOf<String, Rect>()
  private var pointer: Offset? = null
  var hovered by mutableStateOf<String?>(null)
    private set

  fun updatePointer(point: Offset?) {
    pointer = point
    hovered = point?.let { p -> bounds.entries.firstOrNull { it.value.contains(p) }?.key }
  }

  internal fun place(tag: String, rect: Rect) {
    bounds[tag] = rect
    updatePointer(pointer)
  }

  internal fun remove(tag: String) {
    bounds.remove(tag)
    updatePointer(pointer)
  }
}
