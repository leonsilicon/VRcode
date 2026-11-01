package dev.improve.vrcode.cmux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.improve.vrcode.speech.Frame
import dev.improve.vrcode.speech.Kind
import dev.improve.vrcode.speech.SpeechEvent
import dev.improve.vrcode.speech.SpeechLink
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A separate control connection through the existing authenticated service tunnel. */
class CmuxLink(scope: CoroutineScope, private val onConnected: () -> Unit, private val onStream: (JSONObject) -> Unit) {
  var connected by mutableStateOf(false)
    private set
  private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
  private var endpoint: Pair<String, Int>? = null
  private val transport = SpeechLink(scope, ::frame, ::event)

  fun connect(host: String, port: Int) {
    close()
    endpoint = host to port
    transport.connect(host, port)
  }

  private fun event(event: SpeechEvent) {
    if (event is SpeechEvent.Disconnected) disconnected(event.reason)
  }

  private fun frame(frame: Frame) {
    val value = runCatching { JSONObject(String(frame.body, Charsets.UTF_8)) }.getOrNull() ?: return
    if (frame.kind == Kind.WELCOME) {
      val available = value.optBoolean("cmux", false)
      if (!connected && available) { connected = true; onConnected() }
    } else if (frame.kind == Kind.CMUX_EVENT) {
      when (value.optString("type")) {
        "response" -> pending.remove(value.optString("id"))?.complete(value)
        "connection_end" -> {
          failPending(value.optString("message", "cmux disconnected"))
          onStream(value)
        }
        else -> onStream(value)
      }
    }
  }

  /** Each invocation is sent once. A timeout/disconnect never silently repeats a mutation. */
  suspend fun call(operation: String, params: JSONObject = JSONObject(), mutation: Boolean = false,
    key: String = UUID.randomUUID().toString()): JSONObject {
    check(connected) { "Connect to an updated VRcode Mac service to use terminal actions." }
    val id = UUID.randomUUID().toString()
    val response = CompletableDeferred<JSONObject>()
    pending[id] = response
    val request = JSONObject().put("protocol", "cmux.protocol/2").put("type", "request")
      .put("id", id).put("operation", operation).put("params", params)
    if (mutation) request.put("idempotency_key", key)
    try {
      check(transport.send(Kind.CMUX_REQUEST, 0, request)) { "Control connection is unavailable. The action was not queued." }
      // Waiting for a process/pattern follows cmux's timeout (or cancellation), rather than a UI deadline.
      val result = if (operation in listOf("terminal.wait", "terminal.wait_exit")) response.await()
        else withTimeout(35_000) { response.await() }
      if (!result.optBoolean("ok")) {
        val error = result.optJSONObject("error")
        throw IOException("${error?.optString("message") ?: "cmux rejected the action"} (${error?.optString("code") ?: "unknown"})")
      }
      return result
    } catch (timeout: TimeoutCancellationException) {
      throw IOException("No response from cmux. Refresh before repeating the action; its outcome may be unknown.", timeout)
    } finally { pending.remove(id) }
  }

  fun cancelPending() {
    // Closing the owning transport guarantees cleanup of pending waits and viewer leases.
    // A fresh connection may refresh state; it never resends the cancelled action.
    val address = endpoint
    close()
    if (address != null) connect(address.first, address.second)
  }

  private fun disconnected(reason: String) {
    connected = false
    failPending(reason)
  }

  private fun failPending(reason: String) {
    val requests = pending.values.toList()
    pending.clear()
    requests.forEach { it.completeExceptionally(IOException("$reason. Refresh state before repeating an action; its outcome may be unknown.")) }
  }

  fun close() {
    endpoint = null
    transport.close()
    disconnected("Control connection closed")
  }
}
