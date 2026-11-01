package dev.improve.vrcode.cmux

import dev.improve.vrcode.TerminalSize
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Creates a separate screen with a fresh login shell at the requested directory and grid size. */
internal class TerminalCreation(
  private val call: suspend (String, JSONObject, Boolean) -> JSONObject,
  private val onLookupFailure: (Exception) -> Unit,
) {
  suspend fun create(sourceTerminal: String?, name: String?, size: TerminalSize, isCurrent: () -> Boolean): JSONObject? {
    val directory = if (sourceTerminal == null) null else try {
      val result = call("terminal.process.get", JSONObject().put("terminal", sourceTerminal), false).getJSONObject("result")
      val process = result.optJSONObject("value") ?: result
      sequenceOf("foreground_cwd", "cwd").mapNotNull { process.opt(it) as? String }.firstOrNull { it.isNotBlank() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { onLookupFailure(error); null }
    if (!isCurrent()) return null
    val params = JSONObject().apply { if (name != null) put("name", name) }
    val operation = if (directory == null) "screen.create" else {
      // screen.create cannot set cwd. A terminal in an explicitly new, empty workspace can;
      // omitting its workspace would let cmux choose the existing active pane instead.
      val workspaceResult = call("workspace.create", JSONObject().put("initial_content", "empty"), true).getJSONObject("result")
      val workspace = (workspaceResult.optJSONObject("value") ?: workspaceResult).getString("workspace_id")
      if (!isCurrent()) return null
      params.put("workspace", workspace).put("cwd", directory).put("cols", size.cols).put("rows", size.rows)
      "tab.create_terminal"
    }
    // Never retry a mutation or remove its workspace after a lost reply: creation may have run.
    val result = call(operation, params, true).getJSONObject("result")
    return result.optJSONObject("value") ?: result
  }
}
