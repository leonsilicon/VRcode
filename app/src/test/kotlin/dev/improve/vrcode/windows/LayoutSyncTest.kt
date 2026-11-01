package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.WindowMode
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayoutSyncTest {
  private val a = "screen_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  private val b = "screen_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  private val identity = ProjectionIdentity.forDevice("test-device", "launch-1")
  private var now = 0L

  /** The two cmux operations, with cmux's revision check and error text. */
  private inner class FakeCmux {
    var projection: JSONObject? = null
    var revision = 0L
    val puts = mutableListOf<JSONObject>()
    var streams = 0
    var streamId = ""

    fun store(layout: SceneLayout) {
      revision++
      projection = layout.toJson()
    }

    fun snapshot() = JSONObject().put("id", identity.projectionId).put("projection", projection)
      .put("projection_revision", revision.toString()).put("frontend_id", identity.frontendId)
      .put("window_id", identity.windowId).put("generation", "launch-0")

    suspend fun call(operation: String, params: JSONObject, mutation: Boolean): JSONObject = when (operation) {
      "frontend_projection.get" -> {
        assertEquals(identity.projectionId, params.getString("frontend_projection"))
        if (projection == null) throw IOException("no frontend_projection matches (selector.not_found)")
        JSONObject().put("ok", true).put("result", snapshot())
      }
      "frontend_projection.put" -> {
        assertTrue(mutation)
        puts += params
        val expected = params.optString("expected_projection_revision").toLongOrNull()
        if (params.has("expected_projection_revision") && expected != revision) {
          throw IOException("projection revision conflict: expected $expected, current $revision (operation.failed)")
        }
        revision++
        projection = params.getJSONObject("projection")
        JSONObject().put("ok", true).put("result", JSONObject().put("value", snapshot()))
      }
      "session.events" -> { streams++; streamId = params.getString("stream_id"); JSONObject().put("ok", true).put("result", JSONObject()) }
      else -> error("unexpected $operation")
    }
  }

  private val cmux = FakeCmux()
  private val sync = LayoutSync(CoroutineScope(Dispatchers.Unconfined), identity, cmux::call, { true }, { now }, {})

  private fun pose(x: Float) = Pose(Vector3(x, 1.4f, -1.5f))
  private fun stored() = SceneLayout.fromJson(cmux.projection)!!

  @Test fun identityIsStableValidAndPerDevice() {
    val again = ProjectionIdentity.forDevice("test-device", "launch-2")
    assertEquals(identity.projectionId, again.projectionId)
    assertEquals(identity.windowId, again.windowId)
    assertNotEquals(identity.generation, again.generation)
    assertTrue(Regex("projection_[0-9a-f]{32}").matches(identity.projectionId))
    assertNotEquals(identity.projectionId, ProjectionIdentity.forDevice("other-device").projectionId)
  }

  @Test fun cmuxPlacementsReplaceProvisionalOnesAndNewScreensAreSaved() {
    cmux.store(SceneLayout().seeded(a, WindowMode.Fixed, pose(1f), null))
    sync.seed(a, WindowMode.Fixed, pose(9f), null)
    sync.seed(b, WindowMode.Fixed, pose(2f), null)
    sync.tick(null)
    assertTrue(cmux.puts.isEmpty()) // nothing is written before cmux answers
    sync.onConnected()
    assertTrue(sync.loaded)
    assertEquals(1, cmux.streams)
    assertEquals(1f, sync.layout.windows[a]!!.pose.t.x)
    now = 1000
    sync.tick(null)
    assertEquals(1, cmux.puts.size)
    assertEquals("1", cmux.puts[0].getString("expected_projection_revision"))
    assertEquals(identity.generation, cmux.puts[0].getString("generation"))
    assertEquals(1f, stored().windows[a]!!.pose.t.x)
    assertEquals(2f, stored().windows[b]!!.pose.t.x)
    now = 2000
    sync.tick(null)
    assertEquals(1, cmux.puts.size) // nothing left to save
  }

  @Test fun aDragIsWrittenOnceAfterItSettles() {
    cmux.store(SceneLayout().seeded(a, WindowMode.Fixed, pose(1f), null))
    sync.onConnected()
    for (step in 0..10) {
      now = step * 16L
      sync.edit { it.moved(a, pose(1f + step / 10f), null) }
      sync.tick(null)
    }
    assertTrue(cmux.puts.isEmpty())
    now += 600
    sync.tick(null)
    assertEquals(1, cmux.puts.size)
    assertEquals(2f, stored().windows[a]!!.pose.t.x)
  }

  @Test fun aConflictingWriteKeepsBothChanges() {
    cmux.store(SceneLayout().seeded(a, WindowMode.Fixed, pose(1f), null).seeded(b, WindowMode.Fixed, pose(2f), null))
    sync.onConnected()
    // Another writer moves b; this headset moves a before it hears about it.
    cmux.store(stored().moved(b, pose(5f), null))
    sync.edit { it.moved(a, pose(3f), null) }
    now = 1000
    sync.tick(null) // rejected as stale, then cmux's layout is read and merged
    assertEquals(5f, sync.layout.windows[b]!!.pose.t.x)
    assertEquals(3f, sync.layout.windows[a]!!.pose.t.x)
    sync.tick(null)
    assertEquals(2, cmux.puts.size)
    assertEquals(3f, stored().windows[a]!!.pose.t.x)
    assertEquals(5f, stored().windows[b]!!.pose.t.x)
  }

  @Test fun streamedChangesAreRenderedAndStaleOnesIgnored() {
    cmux.store(SceneLayout().seeded(a, WindowMode.Fixed, pose(1f), null))
    sync.onConnected()
    fun delta(x: Float, revision: Long) = JSONObject().put("type", "stream_item").put("stream_id", cmux.streamId)
      .put("item", JSONObject().put("kind", "delta").put("changes", JSONArray().put(JSONObject()
        .put("resource", "frontend_projection").put("id", identity.projectionId).put("kind", "upsert")
        .put("value", JSONObject().put("projection", SceneLayout(mapOf(a to WindowPlacement(WindowMode.Follow, pose(x)))).toJson())
          .put("projection_revision", revision.toString())))))
    sync.onStream(delta(4f, 2))
    assertEquals(4f, sync.layout.windows[a]!!.pose.t.x)
    assertEquals(WindowMode.Follow, sync.layout.windows[a]!!.mode)
    sync.onStream(delta(7f, 2)) // an echo or a late delta never moves a window backwards
    assertEquals(4f, sync.layout.windows[a]!!.pose.t.x)
    now = 1000
    sync.tick(null)
    assertTrue(cmux.puts.isEmpty()) // rendering cmux's change is not a local change
  }

  @Test fun noLayoutYetStartsEmptyAndCreatesIt() {
    sync.seed(a, WindowMode.Fixed, pose(1f), null)
    sync.onConnected()
    assertTrue(sync.loaded)
    now = 1000
    sync.tick(setOf(a))
    assertFalse(cmux.puts[0].has("expected_projection_revision"))
    assertEquals(setOf(a), stored().windows.keys)
  }

  @Test fun anotherSchemaIsShownAsDefaultsAndNeverOverwritten() {
    cmux.projection = JSONObject().put("schema_version", 2).put("space", "local_floor")
    cmux.revision = 4
    sync.seed(a, WindowMode.Fixed, pose(1f), null)
    sync.onConnected()
    assertTrue(sync.readOnly)
    sync.edit { it.moved(a, pose(2f), null) }
    now = 1000
    sync.tick(null)
    assertTrue(cmux.puts.isEmpty())
    assertEquals(2, cmux.projection!!.getInt("schema_version"))
    assertEquals(2f, sync.layout.windows[a]!!.pose.t.x)
  }

  @Test fun closedScreensAreDroppedWhenSaving() {
    cmux.store(SceneLayout().seeded(a, WindowMode.Fixed, pose(1f), null).seeded(b, WindowMode.Fixed, pose(2f), null))
    sync.onConnected()
    sync.edit { it.minimized(a) }
    now = 1000
    sync.tick(setOf(a))
    assertEquals(setOf(a), stored().windows.keys)
    assertTrue(stored().windows[a]!!.minimized)
  }

}
