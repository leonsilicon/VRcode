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
import dev.improve.vrcode.windows.TerminalWindows
import dev.improve.vrcode.windows.TerminalActionNotice
import dev.improve.vrcode.windows.TerminalInfoPanels
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
  private val terminalWindows: TerminalWindows,
  private val defaultTerminalSize: () -> TerminalSize,
) {
  private val catalog = CmuxCatalog(activity)
  private val link = CmuxLink(scope, ::controlConnected, ::stream)
  /** The first cmux session read of this app run has happened (see [controlConnected]). */
  private var startupChecked = false
  private val terminalCreation = TerminalCreation(
    { operation, params, mutation -> link.call(operation, params, mutation) },
    { error -> android.util.Log.w("vrcode", "could not read the source terminal's directory; using the default", error) },
  )
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
  private val streamNotices = mutableMapOf<String, TerminalActionNotice<ScreenWindow>>()
  private var actionId = 0
  private var clickTarget: WindowHit? = null
  private var targetWindow: ScreenWindow? = null
  private val info = TerminalInfoPanels(xmux)
  private var actionNotice: TerminalActionNotice<ScreenWindow>? = null
  var isOpen by mutableStateOf(false)
    private set

  fun connect(host: String, port: Int) {
    disconnect()
    link.connect(host, port)
  }

  /**
   * Refreshes the Actions panel's snapshot and, on the first connection of this app run, opens a
   * terminal when the cmux session has no screens, so launching VRcode always shows one. Later
   * reconnects never add one: the user may have closed them all.
   */
  private fun controlConnected() {
    refresh()
    if (startupChecked) return
    val current = generation
    scope.launch {
      // Screens are the windows the headset shows. Not terminals: cmux also lists terminals in no tab.
      val screens = try {
        link.call("session.snapshot").getJSONObject("result").optJSONArray("screens")?.length() ?: 0
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        android.util.Log.w("vrcode", "startup: could not read the cmux session; checking again on the next connection", error)
        return@launch
      }
      if (current != generation || startupChecked) return@launch
      startupChecked = true
      android.util.Log.i("vrcode", "startup: the cmux session has $screens screens${if (screens == 0) "; opening a terminal" else ""}")
      if (screens == 0) newTerminal()
    }
  }

  /** Whether the Mac service's cmux control connection is up. */
  val controlConnected: Boolean get() = link.connected

  /** Pins [cmuxTerminal]'s grid in cmux, over any other viewer's size (the Mac service's `vrcode.terminal.size.set`). */
  suspend fun pinTerminalSize(cmuxTerminal: String, cols: Int, rows: Int): JSONObject {
    val current = generation
    val params = JSONObject().put("terminal", cmuxTerminal).put("cols", cols).put("rows", rows)
    val notice = info.start(windowFor(params), "Resize terminal", "Setting the terminal to $cols × $rows…")
    try {
      return link.call("vrcode.terminal.size.set", params).getJSONObject("result").also {
        if (current == generation) info.finish(notice, "Terminal resized to $cols × $rows")
      }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
      if (current == generation) info.finish(notice, "Could not set the terminal size: ${error.message}", failed = true)
      throw error
    }
  }

  fun disconnect() {
    generation++
    closingScreens.clear()
    task?.cancel(); task = null
    link.close()
    snapshot = null
    streams = emptyList()
    streamNotices.clear()
    busy = false
    target = emptyMap()
    clickTarget = null
    targetWindow = null
    text = null
    actionNotice = null
    info.clear()
    hide()
  }

  fun open(hit: WindowHit) {
    open(hit.window, hit.pane)
    clickTarget = hit
    if (hit.row >= 0 && hit.col >= 0) target = target + mapOf("row" to hit.row.toString(), "col" to hit.col.toString())
  }

  fun open(window: ScreenWindow?, paneId: PaneId? = null) {
    clickTarget = null
    targetWindow = window
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

  /** Info boxes never change focus or open the action picker. */
  fun tick(visible: Boolean) = info.tick(visible)

  fun infoHeight(window: ScreenWindow): Float = info.occupiedHeight(window)

  private fun action(label: String? = null, window: ScreenWindow? = targetWindow,
    block: suspend (TerminalActionNotice<ScreenWindow>?) -> Unit) {
    if (busy) return
    val current = generation
    val id = ++actionId
    val notice = label?.let { info.start(window, it, "Running…") }
    actionNotice = notice
    busy = true
    status = ""
    task = scope.launch {
      try {
        block(notice)
        if (current == generation && id == actionId && notice != null && notice.state == TerminalActionNotice.State.Running && notice !in streamNotices.values) {
          info.finish(notice, status.ifBlank { "Completed" })
        }
      }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        if (current == generation && id == actionId) {
          status = error.message ?: "Action failed"
          if (notice != null) info.finish(notice, status, failed = true)
        }
      }
      finally { if (current == generation && id == actionId) { busy = false; actionNotice = null } }
    }
  }

  private suspend fun updateSnapshot() {
    snapshot = link.call("session.snapshot").getJSONObject("result")
  }

  fun refresh() = action { updateSnapshot(); status = "Session updated" }

  /**
   * Closes the focused window's cmux screen, as its title bar's × does (the left palm menu's Close
   * terminal). Only a shown, focused window: with none it closes nothing rather than pick another.
   */
  fun closeFocusedScreen() {
    val window = xmux.focused?.takeIf { it.visible && it.session != null }
    if (window == null) {
      android.util.Log.i("VrcodeWindow", "close terminal: no focused window")
      return
    }
    closeScreen(window)
  }

  /** Close this exact cmux screen, even if another screen/pane has focus when the reply arrives. */
  fun closeScreen(window: ScreenWindow) {
    val screen = window.screen?.let { window.session?.topology?.value?.screen(it) }?.cmuxId ?: return
    if (!closingScreens.add(screen)) return
    val current = generation
    val notice = info.start(window, "Close terminal", "Closing the screen…")
    scope.launch {
      try {
        link.call("screen.close", JSONObject().put("screen", screen), mutation = true)
        if (current == generation) info.finish(notice, "Terminal closed")
        android.util.Log.i("VrcodeWindow", "closed cmux screen $screen")
        // xmux's topology stream removes the window and dock entry; do not hide it optimistically.
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        android.util.Log.w("VrcodeWindow", "could not close cmux screen $screen", error)
        if (current != generation) return@launch
        info.finish(notice, "Could not close the screen: ${error.message ?: "the action failed"}", failed = true)
      } finally { if (current == generation) closingScreens.remove(screen) }
    }
  }

  /**
   * Opens a new cmux screen in front of the current gaze, focuses it once mounted,
   * and types [command] (if any) into its fresh login shell, so the shell remains when the command
   * exits. It inherits the focused terminal's directory and window dimensions when available. Nothing
   * reaches an existing terminal. Creation is sent once; a failure is shown rather than retried.
   */
  fun newTerminal(command: String? = null, name: String? = null) {
    val window = focusedWindow()
    newTerminal(command, name, terminalSource(window), window)
  }

  private fun focusedWindow() = xmux.focused?.takeIf { it.visible && it.session != null }
    ?: xmux.windows.firstOrNull { it.visible && it.session != null }

  /** Snapshot before any requests: changing focus or resizing the source must not retarget creation. */
  private data class TerminalSource(val terminal: String, val size: TerminalSize, val widthM: Float, val heightM: Float, val scale: Float)

  private fun terminalSource(window: ScreenWindow?): TerminalSource? {
    val session = window?.session ?: return null
    val id = window.focusedTerminal ?: return null
    val info = session.topology.value.terminals[id] ?: return null
    val handle = session.terminal(id)
    val grid = handle.grid.value
    val size = if (handle.attach.value == AttachStatus.Attached) TerminalSize(grid.cols, grid.rows) else TerminalSize(info.cols, info.rows)
    return TerminalSource(info.cmuxId, size, window.widthM, window.heightM, window.scale)
  }

  private fun newTerminal(command: String?, name: String?, source: TerminalSource?, sourceWindow: ScreenWindow?,
    notice: TerminalActionNotice<ScreenWindow> = info.start(sourceWindow, name ?: "New terminal", "Opening a terminal…")) {
    val current = generation
    val size = source?.size ?: defaultTerminalSize().sanitized()
    scope.launch {
      try {
        val created = terminalCreation.create(source?.terminal, name, size) { current == generation } ?: return@launch
        if (current != generation) return@launch
        terminalWindows.openInFront(created.getString("screen_id"))
        configureCreatedTerminal(created, size, current, focus = true, source = source) { notice.target = it }
        if (current != generation) return@launch
        val terminal = created.getString("terminal_id")
        if (command != null) link.call("terminal.input.write", JSONObject().put("terminal", terminal).put("text", "$command\r"), mutation = true)
        if (current == generation) info.finish(notice, if (command == null) "Terminal opened" else "${name ?: command} launched in a new terminal")
        android.util.Log.i("vrcode", "new terminal ${name ?: "(shell)"}: screen ${created.getString("screen_id")}, based on ${source?.terminal ?: "(default)"}")
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        android.util.Log.w("vrcode", "new terminal ${name ?: "(shell)"} failed", error)
        if (current != generation) return@launch
        info.finish(notice, "Could not open ${name ?: "a new terminal"}: ${error.message ?: "the action failed"}", failed = true)
      }
    }
  }

  /**
   * Runs [command] in the terminal keyboard input goes to when its shell waits at an empty prompt
   * (no program running, nothing typed), and otherwise opens it in a new terminal as [newTerminal]
   * does. A check that fails or cannot be read also opens a new terminal, so the command is never
   * typed into a running program or after half-typed text.
   */
  fun runCommand(command: String, name: String) {
    val window = focusedWindow()
    val source = terminalSource(window) ?: return newTerminal(command, name, null, window)
    val terminal = source.terminal
    val current = generation
    val notice = info.start(window, name, "Checking the terminal…")
    scope.launch {
      val busy = try { busyReason(terminal) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { "could not be checked (${error.message})" }
      if (current != generation) return@launch
      if (busy != null) {
        android.util.Log.i("vrcode", "$name: focused terminal $terminal $busy; opening a new terminal")
        notice.message = "Opening a new terminal because the current terminal is busy…"
        return@launch newTerminal(command, name, source, window, notice)
      }
      try {
        notice.message = "Launching $name…"
        link.call("terminal.input.write", JSONObject().put("terminal", terminal).put("text", "$command\r"), mutation = true)
        if (current == generation) info.finish(notice, "$name launched")
        android.util.Log.i("vrcode", "$name: ran in the focused terminal $terminal at its empty prompt")
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        android.util.Log.w("vrcode", "$name in the focused terminal $terminal failed", error)
        if (current != generation) return@launch
        info.finish(notice, "Could not run $name: ${error.message ?: "the action failed"}", failed = true)
      }
    }
  }

  /** Why [terminal] (a cmux id) cannot take a typed command now, or null at an empty shell prompt. */
  private suspend fun busyReason(terminal: String): String? {
    val process = link.call("terminal.process.get", JSONObject().put("terminal", terminal)).getJSONObject("result")
    val shell = process.optString("executable").ifEmpty { process.optJSONArray("argv")?.optString(0) }
    val foreground = if (process.isNull("foreground_executable")) null else process.getString("foreground_executable")
    if (!ShellPrompt.shellInForeground(shell, foreground)) return "is running ${foreground ?: "an unknown process"} (shell $shell)"
    val screen = link.call("terminal.screen.read", JSONObject().put("terminal", terminal)).getJSONObject("result")
    val empty = ShellPrompt.emptyPrompt(screen.getString("text"), screen.getInt("cursor_row"), screen.getInt("cursor_col"), screen.getInt("cols"))
    return if (empty) null else "has text at its prompt (or no prompt it recognizes)"
  }

  /** Apply the inherited or default size to the exact returned terminal, once its viewer attaches. */
  private suspend fun configureCreatedTerminal(created: JSONObject, size: TerminalSize, current: Int, focus: Boolean = false, source: TerminalSource? = null,
    onWindow: (ScreenWindow) -> Unit = {}) {
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
        onWindow(window)
        val handle = session.terminal(terminal.id)
        val attached = withTimeoutOrNull(5_000) {
          handle.attach.first { it == AttachStatus.Attached || it == AttachStatus.Error || it == AttachStatus.Detached }
        }
        if (current != generation) return
        check(attached == AttachStatus.Attached) { "Terminal opened, but its viewer did not attach to apply its size" }
        handle.resize(size.cols, size.rows)
        if (source != null) terminalWindows.inheritSize(window, source.widthM, source.heightM, source.scale)
        if (focus) xmux.focus(window)
        android.util.Log.i("VrcodeWindow", "new terminal $cmuxTerminal size ${size.cols}x${size.rows}")
        return
      }
      kotlinx.coroutines.delay(100)
    }
    error("Terminal opened, but its window did not mount to apply its size")
  }

  private fun run(name: String, params: JSONObject) = action(catalog.operation(name).label, windowFor(params)) { notice ->
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
      configureCreatedTerminal(created, size, current) { notice?.target = it }
    }
    if (current != generation) return@action
    status = "Completed: ${operation.label}"
    result.optJSONObject("result")?.optString("stream_id")?.takeIf { it.isNotEmpty() }?.let {
      streams = (streams + it).distinct()
      if (notice != null) { notice.message = "Live stream running"; streamNotices[it] = notice }
    }
    if (name == "stream.cancel") {
      val id = params.getString("stream")
      streams = streams - id
      streamNotices.remove(id)?.let { info.finish(it, "Live stream stopped") }
    }
    if (!operation.mutation && operation.kind != "stream_open") text = result.opt("result").let {
      when (it) { is JSONObject -> it.toString(2); else -> it?.toString() ?: "" }
    }
    if (name != "session.shutdown") updateSnapshot()
  }

  /** Resolve explicit selectors before starting; changing focus cannot redirect an in-flight notice. */
  private fun windowFor(params: JSONObject): ScreenWindow? {
    params.optString("stream").takeIf(String::isNotEmpty)?.let { streamNotices[it]?.target?.let { window -> return window } }
    val key = listOf("terminal", "tab", "pane", "screen", "workspace").firstOrNull { params.has(it) && !params.isNull(it) }
      ?: return targetWindow
    val id = params.getString(key)
    return xmux.windows.firstOrNull { window ->
      val topology = window.session?.topology?.value ?: return@firstOrNull false
      val screen = window.screen?.let(topology::screen) ?: return@firstOrNull false
      when (key) {
        "screen" -> screen.cmuxId == id
        "workspace" -> topology.workspaces.firstOrNull { it.id == screen.workspace }?.cmuxId == id
        "pane" -> screen.panes.any { it.cmuxId == id }
        "tab" -> screen.panes.any { pane -> pane.tabs.any { it.cmuxId == id } }
        else -> screen.panes.any { pane -> pane.tabs.any { topology.terminals[it.terminal]?.cmuxId == id } }
      }
    }
  }

  private fun terminalParams(terminal: String?) = JSONObject().put("terminal", terminal ?: error("Choose a terminal first"))

  private fun copy(mode: String) {
    val terminal = target["terminal"]
    action(if (mode == "screen") "Copy screen" else "Copy history") {
      val result = link.call("terminal.copy", terminalParams(terminal).put("mode", mode)).getJSONObject("result").getString("text")
      activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", result))
      link.call("vrcode.clipboard.write", JSONObject().put("text", result))
      status = "Copied ${result.length} characters to the Quest and Mac clipboards"
    }
  }

  private fun paste(fromMac: Boolean) {
    // Capture the clipboard on the Quest while this panel is focused and capture the target now.
    val clipboard = if (!fromMac) activity.getSystemService(ClipboardManager::class.java).primaryClip
      ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString() else null
    val terminal = target["terminal"]
    action(if (fromMac) "Paste from Mac" else "Paste") {
      val params = terminalParams(terminal)
      val value = if (fromMac) link.call("vrcode.clipboard.read").getJSONObject("result").getString("text") else clipboard.orEmpty()
      require(value.isNotEmpty()) { "The clipboard is empty" }
      link.call("terminal.input.write", params.put("text", value), mutation = true)
      status = "Pasted ${value.length} characters"
    }
  }

  private fun readText() {
    val terminal = target["terminal"]
    action("Read terminal text") {
      text = link.call("terminal.copy", terminalParams(terminal).put("mode", "scrollback")).getJSONObject("result").getString("text")
      status = "Select text to copy, or use Find next. Choose Select / find text again to update this snapshot."
    }
  }

  private fun appClick() {
    val hit = clickTarget ?: return
    val terminal = hit.terminal?.let { hit.window.session?.terminal(it) } ?: return
    // The xmux outbox queues both events together without waiting a network round trip between them.
    terminal.mouse(MouseEvent(MouseAction.Press, 2, hit.row, hit.col))
    terminal.mouse(MouseEvent(MouseAction.Release, 2, hit.row, hit.col))
    status = "Sent right-click to the terminal app"
    info.finish(info.start(hit.window, "Terminal right-click", "Sending right-click…"), status)
    hide()
  }

  private fun cancelAction() {
    actionId++
    task?.cancel()
    actionNotice?.let { info.finish(it, "Stopped waiting. The action may have completed; check the terminal before retrying.", failed = true) }
    actionNotice = null
    link.cancelPending()
    busy = false
    status = "Stopped waiting. Refresh to check whether the action completed."
  }

  private fun stream(event: JSONObject) {
    if (event.optString("type") == "connection_end") {
      streams = emptyList()
      status = event.optString("message")
      streamNotices.values.forEach { info.finish(it, status.ifBlank { "Live stream disconnected" }, failed = true) }
      streamNotices.clear()
      return
    }
    val id = event.optString("stream_id")
    if (id.isNotEmpty()) streams = if (event.optString("type") == "stream_end") streams - id else (streams + id).distinct()
    if (event.optString("type") == "stream_end") streamNotices.remove(id)?.let {
      val error = event.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
      info.finish(it, error ?: event.optString("message").ifBlank { "Live stream ended" }, failed = error != null)
    }
    // Keep one bounded latest item, rather than accumulate terminal/browser frames in UI state.
    if (isOpen) text = event.toString(2).take(32_000)
  }

  fun close() {
    disconnect()
    releasePanel()
  }

  fun releasePanel() {
    hide()
    info.close()
    panel?.destroy(); panel = null
  }
}
