package dev.improve.vrcode.speech

import dev.improve.vrcode.AppSettings
import dev.improve.vrcode.SettingsSync
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors Settings into VRcode Mac on the Mac (vrcode protocol SETTINGS / SETTINGS_SET /
 * SETTINGS_ACTION) over its own connection through the authenticated service tunnel.
 *
 * The Quest stays the source of truth: it publishes its schema and values whenever the Mac
 * service welcomes it and after every change. Values from the Mac go through [onValues], the same
 * path as a change in the Settings panel; [onAction] runs a schema action such as arranging windows.
 */
class SettingsLink(
  scope: CoroutineScope,
  private val device: String,
  private val current: () -> AppSettings,
  private val onValues: (Map<String, Any?>) -> Unit,
  private val onAction: (String) -> Unit,
) {
  private var ready = false
  private val transport = SpeechLink(scope, ::frame) { event -> if (event is SpeechEvent.Disconnected) ready = false }

  fun connect(host: String, port: Int) { ready = false; transport.connect(host, port) }

  fun close() { ready = false; transport.close() }

  /** Sends the full state; call after every change, including ones that came from the Mac. */
  fun publish(settings: AppSettings = current()) {
    if (ready) transport.send(Kind.SETTINGS, 0, SettingsSync.state(settings, device).toByteArray())
  }

  private fun frame(frame: Frame) {
    val json = runCatching { JSONObject(String(frame.body, Charsets.UTF_8)) }.getOrNull() ?: return
    when (frame.kind) {
      // Older Mac services do not mirror settings and never set this flag.
      Kind.WELCOME -> if (json.optBoolean("settings", false)) { ready = true; publish() }
      Kind.SETTINGS_SET -> json.optJSONObject("values")?.let { values ->
        onValues(values.keys().asSequence().associateWith { key -> plain(values.opt(key)) })
      }
      Kind.SETTINGS_ACTION -> onAction(json.optString("action"))
    }
  }

  /** A JSON value as [SettingsSync.apply] takes it: JSON null as null and arrays (quick inputs) as lists. */
  private fun plain(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONArray -> List(value.length()) { plain(value.opt(it)) }
    else -> value
  }
}
