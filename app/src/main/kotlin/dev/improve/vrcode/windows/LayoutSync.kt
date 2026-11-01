package dev.improve.vrcode.windows

import android.os.SystemClock
import android.util.Log
import dev.improve.vrcode.WindowMode
import com.meta.spatial.core.Pose
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Who this headset's scene is to cmux: a durable frontend view `(frontend_id, window_id)` stored
 * as one `frontend_projection`, fenced by a new [generation] each launch. The ids are derived from
 * the device, so nothing about them is stored on the Quest.
 */
class ProjectionIdentity(val projectionId: String, val frontendId: String, val windowId: String, val generation: String) {
  fun selector(): JSONObject = JSONObject().put("frontend_projection", projectionId)

  fun putParams(projection: JSONObject, expectedRevision: Long?): JSONObject = selector()
    .put("projection", projection).put("frontend_id", frontendId).put("window_id", windowId).put("generation", generation)
    .apply { if (expectedRevision != null) put("expected_projection_revision", expectedRevision.toString()) }

  companion object {
    const val FRONTEND_ID = "dev.improve.vrcode"

    fun forDevice(deviceKey: String, generation: String = UUID.randomUUID().toString()): ProjectionIdentity {
      val windowId = "quest-scene-" + sha256("$FRONTEND_ID|$deviceKey").take(16)
      return ProjectionIdentity("projection_" + sha256("$FRONTEND_ID|$windowId").take(32), FRONTEND_ID, windowId, generation)
    }

    private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
  }
}

/**
 * Keeps [layout] equal to the terminal window layout cmux stores for this headset, plus local
 * changes cmux has not confirmed yet. Changes are written once they settle (continuous drags never
 * stream to cmux). Changes made elsewhere, such as another build or `cmux projection put`, arrive
 * on `session.events` and replace what is shown. Main thread only.
 */
class LayoutSync(
  private val scope: CoroutineScope,
  val identity: ProjectionIdentity,
  private val call: suspend (operation: String, params: JSONObject, mutation: Boolean) -> JSONObject,
  private val isConnected: () -> Boolean,
  private val nowMs: () -> Long = SystemClock::uptimeMillis,
  private val log: (String) -> Unit = { Log.i(TAG, it) },
  private val settleMs: Long = 500L,
) {
  /** What vrcode renders. */
  var layout = SceneLayout()
    private set
  /** cmux has answered with this headset's layout, or that it has none yet. */
  var loaded = false
    private set
  /** Another schema version wrote cmux's layout: vrcode shows its own defaults and never overwrites it. */
  var readOnly = false
    private set

  private var revision: Long? = null
  private val dirty = mutableSetOf<String>()
  private var dirtyFullscreen = false
  /** Windows placed locally before cmux answered; cmux's placement replaces them. */
  private val seeds = mutableSetOf<String>()
  private var lastEditMs = 0L
  private var retryAtMs = 0L
  private var failures = 0
  private var writing: Job? = null
  private var refreshing: Job? = null
  private var stream: String? = null
  /** Bumped on every reset, so replies to an older connection are dropped. */
  private var epoch = 0

  /** A new Mac or cmux session: forget what cmux said, keep showing the current layout until it answers. */
  fun reset() {
    epoch++
    writing?.cancel(); writing = null
    refreshing?.cancel(); refreshing = null
    stream = null
    loaded = false
    readOnly = false
    revision = null
    dirty.clear()
    dirtyFullscreen = false
    seeds.clear()
    seeds += layout.windows.keys
    failures = 0
    retryAtMs = 0L
  }

  /** The control connection is (again) up: read the layout and follow changes to it. */
  fun onConnected() = refresh(0L)

  /** A local change. Whatever it changed is written to cmux once changes stop for [settleMs]. */
  fun edit(change: (SceneLayout) -> SceneLayout) {
    val before = layout
    val next = change(before)
    if (next == before) return
    for (screen in before.windows.keys + next.windows.keys) {
      if (before.windows[screen] != next.windows[screen]) { dirty += screen; seeds -= screen }
    }
    if (before.fullscreen != next.fullscreen) dirtyFullscreen = true
    layout = next
    lastEditMs = nowMs()
  }

  /** A window cmux has no placement for appears at [world]. Before cmux answers, the placement is provisional. */
  fun seed(screen: String, mode: WindowMode, world: Pose, head: Pose?) {
    if (screen in layout.windows) return
    layout = layout.seeded(screen, mode, world, head)
    if (loaded) { dirty += screen; lastEditMs = nowMs() } else seeds += screen
  }

  /** Writes settled changes. [live] is every screen cmux has (null when unknown); others are dropped. */
  fun tick(live: Set<String>?) {
    if (nowMs() - lastEditMs >= settleMs) write(live)
  }

  /** Writes now, without waiting for changes to settle (the app is pausing). */
  fun flush(live: Set<String>?) = write(live)

  private fun write(live: Set<String>?) {
    if (!loaded || readOnly || !isConnected() || writing?.isActive == true) return
    if (dirty.isEmpty() && !dirtyFullscreen) return
    if (nowMs() < retryAtMs) return
    if (live != null) layout = layout.pruned(live)
    val sent = layout
    val current = epoch
    val params = identity.putParams(sent.toJson(), revision)
    writing = scope.launch {
      try {
        val result = call("frontend_projection.put", params, true).getJSONObject("result")
        if (current != epoch) return@launch
        val value = result.optJSONObject("value") ?: result
        value.optString("projection_revision").toLongOrNull()?.let { if (revision.let { r -> r == null || it > r }) revision = it }
        dirty.removeAll { layout.windows[it] == sent.windows[it] }
        if (layout.fullscreen == sent.fullscreen) dirtyFullscreen = false
        failures = 0
        log("saved ${sent.windows.size} window placements to cmux (revision $revision)")
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (current != epoch) return@launch
        val message = error.message.orEmpty()
        if (message.contains("revision", ignoreCase = true) && message.contains("conflict", ignoreCase = true)) {
          // Someone else wrote first: read their layout, keep our unsaved changes on top, write again.
          log("layout changed in cmux while saving; merging")
          refresh(0L, readOnly = true)
        } else {
          failures++
          retryAtMs = nowMs() + (1000L shl failures.coerceAtMost(5))
          log("could not save window placements: $message")
        }
      }
    }
  }

  /** Reads the layout and (re)subscribes to its changes, after [delayMs]. */
  private fun refresh(delayMs: Long, readOnly: Boolean = false) {
    val current = epoch
    refreshing?.cancel()
    refreshing = scope.launch {
      if (delayMs > 0) delay(delayMs)
      if (current != epoch || !isConnected()) return@launch
      try {
        val snapshot = try {
          call("frontend_projection.get", identity.selector(), false).getJSONObject("result")
        } catch (error: Exception) {
          if (error is CancellationException || error.message?.contains("selector.not_found") != true) throw error
          null
        }
        if (current != epoch) return@launch
        remote(snapshot, authoritative = true)
        if (!readOnly && stream == null) subscribe(current)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (current != epoch) return@launch
        log("could not read window placements from cmux: ${error.message}")
        refresh(3000L)
      }
    }
  }

  private suspend fun subscribe(current: Int) {
    val id = "stream_" + UUID.randomUUID().toString().replace("-", "")
    stream = id
    try {
      call("session.events", JSONObject().put("stream_id", id), false)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      if (current != epoch || stream != id) return
      stream = null
      log("could not follow cmux layout changes: ${error.message}")
    }
  }

  /** Every non-response event from the control connection. */
  fun onStream(event: JSONObject) {
    when (event.optString("type")) {
      "stream_item" -> if (event.optString("stream_id") == stream) item(event.optJSONObject("item"))
      "stream_end" -> if (event.optString("stream_id") == stream) { stream = null; refresh(2000L) }
      // The Mac lost its cmux socket; it reconnects on the next request.
      "connection_end" -> { stream = null; refresh(2000L) }
    }
  }

  private fun item(item: JSONObject?) {
    item ?: return
    when (item.optString("kind")) {
      "snapshot" -> {
        val projections = item.optJSONObject("snapshot")?.optJSONArray("frontend_projections") ?: return
        val ours = (0 until projections.length()).mapNotNull(projections::optJSONObject)
          .firstOrNull { it.optString("id") == identity.projectionId }
        remote(ours, authoritative = true)
      }
      "delta" -> {
        val changes = item.optJSONArray("changes") ?: return
        for (i in 0 until changes.length()) {
          val change = changes.optJSONObject(i) ?: continue
          if (change.optString("resource") != "frontend_projection" || change.optString("id") != identity.projectionId) continue
          if (change.optString("kind") == "upsert") remote(change.optJSONObject("value"), authoritative = false)
        }
      }
    }
  }

  /**
   * cmux's layout ([snapshot] is a FrontendProjectionSnapshot, null when cmux has none). A full read
   * is [authoritative]; a stream delta applies only if it is newer than what vrcode has seen.
   */
  private fun remote(snapshot: JSONObject?, authoritative: Boolean) {
    val rev = snapshot?.optString("projection_revision")?.toLongOrNull()
    if (snapshot != null && rev == null) return
    if (!authoritative && (rev == null || revision.let { it != null && rev <= it })) return
    val decoded = if (snapshot == null) SceneLayout() else SceneLayout.fromJson(snapshot.opt("projection"))
    if (decoded == null) {
      if (!readOnly) log("cmux has window placements from another vrcode version; not changing them")
      readOnly = true
      loaded = true
      revision = rev
      return
    }
    readOnly = false
    val keep = dirty + seeds.filter { it !in decoded.windows }
    dirty += keep
    seeds.clear()
    val next = decoded.overlaid(layout, keep, dirtyFullscreen)
    if (next != layout) log("cmux layout: ${decoded.windows.size} windows (revision ${rev ?: "none"})")
    layout = next
    revision = rev
    loaded = true
  }

  fun close() {
    reset()
  }

  private companion object {
    const val TAG = "VrcodeWindow"
  }
}
