package dev.improve.vrcode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.improve.xmux.client.MouseAction
import dev.improve.xmux.client.MouseEvent
import dev.improve.xmux.client.PaneId
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.screen.view.ScreenView
import dev.improve.xmux.spatial.XmuxSpatial

/** Installs local pinch-drag selection on each live terminal screen; headers keep SDK input. */
internal class TerminalPointerInput(private val xmux: XmuxSpatial) {
  private val bindings = mutableMapOf<ScreenView, TerminalSelectionInput<TerminalId>>()

  fun update(allowInput: Boolean) {
    val live = xmux.windows.mapNotNull { w -> w.screenView?.let { it to w } }.toMap()
    for ((view, binding) in bindings.toMap()) {
      if (view !in live) {
        binding.close()
        bindings.remove(view)
      }
    }
    for ((view, window) in live) {
      val binding = bindings.getOrPut(view) {
        TerminalSelectionInput(view,
          targetAt = { pane -> window.screen?.let { window.session?.topology?.value?.screen(it) }
            ?.panes?.firstOrNull { it.id.value == pane }?.activeTerminal },
          onFocus = { pane -> xmux.focus(window, PaneId(pane)) },
          onClick = { target, cell ->
            window.session?.terminal(target)?.let { terminal ->
              terminal.mouse(MouseEvent(MouseAction.Press, 0, cell.row, cell.col))
              terminal.mouse(MouseEvent(MouseAction.Release, 0, cell.row, cell.col))
            }
          },
          onCopy = { text ->
            val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal selection", text))
          },
          onStart = { bindings.filterKeys { it !== view }.values.forEach { it.clear() } },
        )
      }
      binding.enabled = allowInput
      binding.validate()
    }
  }

  fun cancel() = bindings.values.forEach { it.cancel() }
  fun clear() = bindings.values.forEach { it.clear() }

  fun close() {
    bindings.values.forEach { it.close() }
    bindings.clear()
  }
}
