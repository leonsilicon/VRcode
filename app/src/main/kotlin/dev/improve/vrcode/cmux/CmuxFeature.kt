package dev.improve.vrcode.cmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.*
import com.meta.spatial.core.Entity
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.CmuxActionsPanel
import dev.improve.vrcode.CmuxCatalog
import dev.improve.vrcode.TerminalSize
import dev.improve.vrcode.cmuxResources
import dev.improve.xmux.client.AttachStatus
import dev.improve.xmux.client.PaneId
import dev.improve.xmux.client.MouseAction
import dev.improve.xmux.client.MouseEvent
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.WindowHit
import dev.improve.xmux.spatial.XmuxSpatial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Quest-owned controls for the daemon. Every operation retains an explicit target. */
class CmuxFeature(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val scope: CoroutineScope,
  private val defaultTerminalSize: () -> TerminalSize,
) {
  private val catalog = CmuxCatalog(activity)
  private val link = CmuxLink(scope, { refresh() }, ::stream)
  private var panel: Entity? = null
  private var snapshot by mutableStateOf<JSONObject?>(null)
  private var target by mutableStateOf<Map<String, String>>(emptyMap())
  private var busy by mutableStateOf(false)
  private var status by mutableStateOf("")
  private var text by mutableStateOf<String?>(null)
  private var streams by mutableStateOf<List<String>>(emptyList())
  private var task: Job? = null
  private var generation = 0
  private val closingScreens = mutableSetOf<String>()
  private var clickTarget: WindowHit? = null
  var isOpen by mutableStateOf(false)
    private set

  fun connect(host: String, port: Int) {
    disconnect()
    link.connect(host, port)
  }

  /** Whether the Mac service's cmux control connection is up. */
  val controlConnected: Boolean get() = link.connected

  /** Pins [cmuxTerminal]'s grid in cmux, over any other viewer's size (the Mac service's `vrcode.terminal.size.set`). */
  suspend fun pinTerminalSize(cmuxTerminal: String, cols: Int, rows: Int): JSONObject =
    link.call("vrcode.terminal.size.set", JSONObject().put("terminal", cmuxTerminal).put("cols", cols).put("rows", rows)).getJSONObject("result")

  fun disconnect() {
    generation++
    closingScreens.clear()
    task?.cancel(); task = null
    link.close()
    snapshot = null
    streams = emptyList()
    busy = false
    target = emptyMap()
    clickTarget = null
    text = null
    hide()
  }

  fun open(hit: WindowHit) {
    open(hit.window, hit.pane)
    clickTarget = hit
    if (hit.row >= 0 && hit.col >= 0) target = target + mapOf("row" to hit.row.toString(), "col" to hit.col.toString())
  }

  fun open(window: ScreenWindow?, paneId: PaneId? = null) {
    clickTarget = null
    target = buildMap {
      val topology = window?.session?.topology?.value
      val screen = window?.screen?.let { topology?.screen(it) }
      if (screen != null && topology != null) {
        put("screen", screen.cmuxId)
        topology.workspaces.firstOrNull { it.id == screen.workspace }?.let { put("workspace", it.cmuxId) }
        val pane = screen.panes.firstOrNull { it.id == (paneId ?: window.focusedPane ?: screen.focusedPane) }
        if (pane != null) {
          put("pane", pane.cmuxId)
          val tab = pane.tabs.firstOrNull { it.id == pane.activeTab }
          if (tab != null) {
            put("tab", tab.cmuxId)
            topology.terminals[tab.terminal]?.let { put("terminal", it.cmuxId) }
          }
        }
      }
    }
    text = null
    if (panel == null) panel = xmux.mountPanel(1100, 850, Placement.InFrontOfHead) {
      CmuxActionsPanel(catalog, target, cmuxResources(snapshot), link.connected, busy, status, text, streams,
        onRun = ::run, onCopy = ::copy, onPaste = ::paste, onReadText = ::readText,
        onRefresh = ::refresh, onStopStream = { run("stream.cancel", JSONObject().put("stream", it)) },
        onAppClick = ::appClick, onCancel = ::cancelAction, onClose = ::hide)
    }
    panel?.setComponent(Visible(true))
    isOpen = true
    refresh()
  }

  fun hide() {
    panel?.setComponent(Visible(false))
    isOpen = false
  }

  private fun action(block: suspend () -> Unit) {
    if (busy) return
    val current = generation
    busy = true
    status = ""
    task = scope.launch {
      try { block() }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { if (current == generation) status = error.message ?: "Action failed" }
      finally { if (current == generation) busy = false }
    }
  }

  private suspend fun updateSnapshot() {
    snapshot = link.call("session.snapshot").getJSONObject("result")
  }

  fun refresh() = action { updateSnapshot(); status = "Session updated" }

  /** Close this exact cmux screen, even if another screen/pane has focus when the reply arrives. */
  fun closeScreen(window: ScreenWindow) {
    val screen = window.screen?.let { window.session?.topology?.value?.screen(it) }?.cmuxId ?: return
    if (!closingScreens.add(screen)) return
    val current = generation
    scope.launch {
      try {
        link.call("screen.close", JSONObject().put("screen", screen), mutation = true)
        android.util.Log.i("VrcodeWindow", "closed cmux screen $screen")
        // xmux's topology stream removes the window and dock entry; do not hide it optimistically.
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        android.util.Log.w("VrcodeWindow", "could not close cmux screen $screen", error)
        if (current != generation) return@launch
        open(window)
        task?.join()
        if (current == generation) status = "Could not close the screen: ${error.message ?: "the action failed"}"
      } finally { if (current == generation) closingScreens.remove(screen) }
    }
  }

  /**
   * Opens a new cmux screen, which xmux mounts as a new floating window, focuses it once mounted,
   * and types [command] (if any) into its fresh login shell, so the shell remains when the command
   * exits. Nothing reaches an existing terminal. Each call sends exactly one `screen.create`; a
   * failure is shown in the Actions panel rather than retried.
   */
  fun newTerminal(command: String? = null, name: String? = null) {
    val current = generation
    val size = defaultTerminalSize().sanitized()
    scope.launch {
      try {
        val params = JSONObject().apply { if (name != null) put("name", name) }
        val created = link.call("screen.create", params, mutation = true).getJSONObject("result").let { it.optJSONObject("value") ?: it }
        configureCreatedTerminal(created, size, current, focus = true)
        if (current != generation) return@launch
        val terminal = created.getString("terminal_id")
        if (command != null) link.call("terminal.input.write", JSONObject().put("terminal", terminal).put("text", "$command\r"), mutation = true)
        android.util.Log.i("vrcode", "new terminal ${name ?: "(shell)"}: screen ${created.getString("screen_id")}")
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        android.util.Log.w("vrcode", "new terminal ${name ?: "(shell)"} failed", error)
        if (current != generation) return@launch
        open(xmux.focused ?: xmux.windows.firstOrNull())
        task?.join() // the panel's own refresh would otherwise replace this message
        if (current == generation) status = "Could not open ${name ?: "a new terminal"}: ${error.message ?: "the action failed"}"
      }
    }
  }

  /** Screen/workspace creation has no size fields. Size only its returned terminal, once attached. */
  private suspend fun configureCreatedTerminal(created: JSONObject, size: TerminalSize, current: Int, focus: Boolean = false) {
    val cmuxScreen = created.getString("screen_id")
    val cmuxTerminal = created.getString("terminal_id")
    repeat(50) {
      if (current != generation) return
      val window = xmux.windows.firstOrNull { w ->
        w.screen?.let { w.session?.topology?.value?.screen(it)?.cmuxId } == cmuxScreen
      }
      val session = window?.session
      val terminal = session?.topology?.value?.terminals?.values?.firstOrNull { it.cmuxId == cmuxTerminal }
      if (window != null && session != null && terminal != null) {
        val handle = session.terminal(terminal.id)
        val attached = withTimeoutOrNull(5_000) {
          handle.attach.first { it == AttachStatus.Attached || it == AttachStatus.Error || it == AttachStatus.Detached }
        }
        if (current != generation) return
        check(attached == AttachStatus.Attached) { "Terminal opened, but its viewer did not attach to apply the default size" }
        handle.resize(size.cols, size.rows)
        if (focus) xmux.focus(window)
        android.util.Log.i("VrcodeWindow", "new terminal $cmuxTerminal size ${size.cols}x${size.rows}")
        return
      }
      kotlinx.coroutines.delay(100)
    }
    error("Terminal opened, but its window did not mount to apply the default size")
  }

  private fun run(name: String, params: JSONObject) = action {
    val operation = catalog.operation(name)
    val current = generation
    val size = defaultTerminalSize().sanitized()
    val request = JSONObject(params.toString())
    // Preserve dimensions entered explicitly in Actions; paired fields are validated by cmux.
    if (name in setOf("workspace.run", "pane.run", "pane.create", "tab.create_terminal", "pane.split") && !request.has("cols") && !request.has("rows")) {
      request.put("cols", size.cols).put("rows", size.rows)
    }
    val result = link.call(name, request, operation.mutation)
    if (name == "screen.create" || (name == "workspace.create" && request.optString("initial_content") == "terminal")) {
      val created = result.getJSONObject("result").let { it.optJSONObject("value") ?: it }
      configureCreatedTerminal(created, size, current)
    }
    if (current != generation) return@action
    status = "Completed: ${operation.label}"
    result.optJSONObject("result")?.optString("stream_id")?.takeIf { it.isNotEmpty() }?.let { streams = (streams + it).distinct() }
    if (name == "stream.cancel") streams = streams - params.getString("stream")
    if (!operation.mutation && operation.kind != "stream_open") text = result.opt("result").let {
      when (it) { is JSONObject -> it.toString(2); else -> it?.toString() ?: "" }
    }
    if (name != "session.shutdown") updateSnapshot()
  }

  private fun terminalParams() = JSONObject().put("terminal", target["terminal"] ?: error("Choose a terminal first"))

  private fun copy(mode: String) = action {
    val result = link.call("terminal.copy", terminalParams().put("mode", mode)).getJSONObject("result").getString("text")
    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", result))
    link.call("vrcode.clipboard.write", JSONObject().put("text", result))
    status = "Copied ${result.length} characters to the Quest and Mac clipboards"
  }

  private fun paste(fromMac: Boolean) {
    // Capture the clipboard on the Quest while this panel is focused and capture the target now.
    val clipboard = if (!fromMac) activity.getSystemService(ClipboardManager::class.java).primaryClip
      ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString() else null
    action {
      val params = terminalParams()
      val value = if (fromMac) link.call("vrcode.clipboard.read").getJSONObject("result").getString("text") else clipboard.orEmpty()
      require(value.isNotEmpty()) { "The clipboard is empty" }
      link.call("terminal.input.write", params.put("text", value), mutation = true)
      status = "Pasted ${value.length} characters"
    }
  }

  private fun readText() = action {
    text = link.call("terminal.copy", terminalParams().put("mode", "scrollback")).getJSONObject("result").getString("text")
    status = "Select text to copy, or use Find next. Choose Select / find text again to update this snapshot."
  }

  private fun appClick() {
    val hit = clickTarget ?: return
    val terminal = hit.terminal?.let { hit.window.session?.terminal(it) } ?: return
    // The xmux outbox queues both events together without waiting a network round trip between them.
    terminal.mouse(MouseEvent(MouseAction.Press, 2, hit.row, hit.col))
    terminal.mouse(MouseEvent(MouseAction.Release, 2, hit.row, hit.col))
    status = "Sent right-click to the terminal app"
    hide()
  }

  private fun cancelAction() {
    link.cancelPending()
    task?.cancel()
    busy = false
    status = "Stopped waiting. Refresh to check whether the action completed."
  }

  private fun stream(event: JSONObject) {
    if (event.optString("type") == "connection_end") {
      streams = emptyList()
      status = event.optString("message")
      return
    }
    val id = event.optString("stream_id")
    if (id.isNotEmpty()) streams = if (event.optString("type") == "stream_end") streams - id else (streams + id).distinct()
    // Keep one bounded latest item, rather than accumulate terminal/browser frames in UI state.
    if (isOpen) text = event.toString(2).take(32_000)
  }

  fun close() {
    disconnect()
    releasePanel()
  }

  fun releasePanel() {
    hide()
    panel?.destroy(); panel = null
  }
}
