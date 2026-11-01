package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.WindowMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SceneLayoutTest {
  private val head = Pose(Vector3(0.2f, 1.6f, -0.3f), Quaternion(0f, 25f, 0f))
  private val movedHead = Pose(Vector3(2f, 1.9f, 0.5f), Quaternion(20f, 90f, 15f))
  private val offset = Pose(Vector3(-0.35f, -0.1f, 1.4f), Quaternion(0f, -10f, 0f))
  private val world = head * offset
  /** Where the user stands for each head; following windows hang from it, not from the gaze. */
  private val user = head.t
  private val walked = movedHead.t
  private val a = "screen_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  private val b = "screen_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

  private fun assertPose(expected: Pose, actual: Pose?) {
    assertNotNull(actual)
    fun assertVector(x: Vector3, y: Vector3) {
      assertEquals(x.x, y.x, 0.0001f); assertEquals(x.y, y.y, 0.0001f); assertEquals(x.z, y.z, 0.0001f)
    }
    assertVector(expected.t, actual!!.t)
    for (axis in listOf(Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f))) assertVector(expected.q * axis, actual.q * axis)
  }

  private fun Pose.shifted(by: Vector3) = Pose(t + by, q)

  private fun layout(mode: WindowMode = WindowMode.Fixed) =
    SceneLayout().seeded(a, mode, world, user).seeded(b, WindowMode.Fixed, Pose(Vector3(1f, 1f, 1f)), user)

  @Test fun fixedWindowsStayInTheRoomWhenTheUserMoves() {
    val l = layout()
    assertPose(world, l.renderPose(a, head, user, 1f, 1f))
    assertPose(world, l.renderPose(a, movedHead, walked, 1f, 1f))
    assertPose(world, l.renderPose(a, null, null, 1f, 1f))
  }

  @Test fun followTravelsWithTheUserButDoesNotTurnWithTheHead() {
    val l = layout(WindowMode.Follow)
    assertPose(world, l.renderPose(a, head, user, 1f, 1f))
    // Looking around (yaw, pitch, roll) from the same spot leaves it where it is.
    assertPose(world, l.renderPose(a, Pose(user, movedHead.q), user, 1f, 1f))
    // Walking carries it along, still facing the same way.
    assertPose(world.shifted(walked - user), l.renderPose(a, movedHead, walked, 1f, 1f))
    assertNull(l.renderPose(a, head, null, 1f, 1f))
  }

  @Test fun switchingModesNeverMovesTheWindow() {
    val following = layout().withMode(a, WindowMode.Follow, user)
    assertPose(world, following.renderPose(a, head, user, 1f, 1f))
    val frozen = following.withMode(a, WindowMode.Fixed, walked)
    assertPose(world.shifted(walked - user), frozen.renderPose(a, head, user, 1f, 1f))
    assertEquals(WindowMode.Fixed, frozen.windows[a]!!.mode)
  }

  @Test fun movesRebaseFollowingWindowsAndLeaveOthersAlone() {
    val arranged = Pose(Vector3(1f, 1f, 2f), Quaternion(0f, 30f, 0f))
    val l = layout(WindowMode.Follow).moved(a, arranged, user)
    assertPose(arranged, l.renderPose(a, head, user, 1f, 1f))
    assertPose(arranged.shifted(walked - user), l.renderPose(a, movedHead, walked, 1f, 1f))
    assertEquals(layout().windows[b], l.windows[b])
  }

  @Test fun fullscreenFollowsTheHeadHidesOthersAndRestoresTheNormalPlacement() {
    val full = layout().fullscreenToggled(a)
    val relative = SceneLayout.fullscreenOffset(1.6f, 0.9f)
    assertPose(movedHead * relative, full.renderPose(a, movedHead, walked, 1.6f, 0.9f))
    assertTrue(full.visible(a))
    assertFalse(full.visible(b))
    // Grabbing the fullscreen window cannot change where it returns to.
    val grabbed = full.moved(a, Pose(Vector3(5f, 5f, 5f)), walked)
    val restored = grabbed.fullscreenToggled(a)
    assertNull(restored.fullscreen)
    assertPose(world, restored.renderPose(a, movedHead, walked, 1.6f, 0.9f))
    assertTrue(restored.visible(b))
  }

  @Test fun theUserAnchorIgnoresLeaningInButKeepsUpWithWalking() {
    val follow = SceneLayout.userFollow()
    val standing = Vector3(0f, 1.6f, 0f)
    val frame = 1f / 72f
    assertEquals(standing, follow.update(standing, 0f))
    // Leaning 30 cm toward a window to read it, and swaying, leave the windows still.
    repeat(216) { assertEquals(standing, follow.update(standing + Vector3(0.3f * (it % 2), -0.08f, 0.1f), frame)) }
    // Walking 4 m at 1.4 m/s: it follows smoothly, never snapping, and never trails by more than ~0.6 m.
    var at = standing
    var previous = standing
    val walk = Vector3(0.6f, 0f, -0.8f) * 1.4f
    repeat((4f / 1.4f / frame).toInt()) {
      at += walk * frame
      val anchor = follow.update(at, frame)
      assertTrue((anchor - previous).length() < 0.05f)
      assertTrue((at - anchor).length() < 0.65f)
      previous = anchor
    }
    // Once the user stops, it catches up and rests.
    repeat(144) { follow.update(at, frame) }
    assertEquals(0f, (follow.position!! - at).length(), 0.005f)
    // A tracking jump snaps, and a reset reappears wherever the user is.
    val far = at + Vector3(5f, 0f, 0f)
    assertEquals(far, follow.update(far, frame))
    follow.reset()
    assertEquals(standing, follow.update(standing, frame))
  }

  @Test fun minimizingLeavesFullscreenAndShowingKeepsTheMode() {
    val l = layout(WindowMode.Follow).fullscreenToggled(a).minimized(a)
    assertNull(l.fullscreen)
    assertFalse(l.visible(a))
    assertTrue(l.visible(b))
    val shown = l.shown(a)
    assertTrue(shown.visible(a))
    assertEquals(WindowMode.Follow, shown.windows[a]!!.mode)
    assertTrue(l.fullscreenToggled(a).visible(a))
  }

  @Test fun fullscreenFitsTallAndWidePanels() {
    val wide = SceneLayout.fullscreenOffset(2.4f, 0.6f)
    val tall = SceneLayout.fullscreenOffset(0.6f, 2.4f)
    assertTrue(tall.t.z > wide.t.z)
    assertEquals(0.6f, SceneLayout.fullscreenOffset(0.1f, 0.1f).t.z, 0f)
  }

  @Test fun jsonRoundTripKeepsPlacementsAndUnknownFields() {
    val l = layout(WindowMode.Follow).fullscreenToggled(b).minimized(a)
    val json = l.toJson()
    json.put("anchor_id", "future-anchor")
    json.getJSONObject("windows").getJSONObject(a).put("theme", "solarized")
    val read = SceneLayout.fromJson(JSONObject(json.toString()))!!
    assertEquals(setOf(a, b), read.windows.keys)
    assertEquals(b, read.fullscreen)
    assertTrue(read.windows[a]!!.minimized)
    assertEquals(WindowMode.Follow, read.windows[a]!!.mode)
    assertPose(l.windows[a]!!.pose, read.windows[a]!!.pose)
    val again = read.toJson()
    assertEquals("future-anchor", again.getString("anchor_id"))
    assertEquals("solarized", again.getJSONObject("windows").getJSONObject(a).getString("theme"))
    assertEquals(1, again.getInt("schema_version"))
    assertEquals("local_floor", again.getString("space"))
  }

  @Test fun theDocumentMatchesTheDocumentedShape() {
    val json = SceneLayout(mapOf(a to WindowPlacement(WindowMode.Fixed, Pose(Vector3(0f, 1.4f, -1.5f))))).toJson()
    val window = json.getJSONObject("windows").getJSONObject(a)
    assertEquals("fixed", window.getString("mode"))
    assertEquals(-1.5, window.getJSONArray("position_m").getDouble(2), 1e-6)
    assertEquals(1.0, window.getJSONArray("orientation_xyzw").getDouble(3), 1e-6)
    assertFalse(window.getBoolean("minimized"))
    assertFalse(json.has("fullscreen_screen_id"))
  }

  @Test fun cornerScaleIsKeptAndWrittenOnlyWhenSet() {
    val scaled = layout().scaled(a, 1.5f)
    assertEquals(1.5f, scaled.windows[a]!!.scale)
    assertEquals(1f, scaled.windows[b]!!.scale)
    val json = scaled.toJson().getJSONObject("windows")
    assertEquals(1.5, json.getJSONObject(a).getDouble("scale"), 1e-6)
    assertFalse(json.getJSONObject(b).has("scale"))
    val read = SceneLayout.fromJson(scaled.toJson())!!
    assertEquals(1.5f, read.windows[a]!!.scale)
    assertNull(read.windows[a]!!.extra)
    // Back to 1 removes the field again; the pose is untouched throughout.
    assertFalse(read.scaled(a, 1f).toJson().getJSONObject("windows").getJSONObject(a).has("scale"))
    assertPose(world, read.renderPose(a, head, user, 1f, 1f))
  }

  @Test fun invalidScalesAreIgnored() {
    val l = layout()
    assertSame(l, l.scaled(a, 0f))
    assertSame(l, l.scaled(a, Float.NaN))
    assertSame(l, l.scaled(a, 50f))
    assertSame(l, l.scaled("screen_missing", 2f))
    val json = l.toJson()
    json.getJSONObject("windows").getJSONObject(a).put("scale", -3)
    assertEquals(1f, SceneLayout.fromJson(json)!!.windows[a]!!.scale)
  }

  @Test fun otherSchemasAreNotReadAndBadWindowsAreSkipped() {
    assertNull(SceneLayout.fromJson(JSONObject().put("schema_version", 2).put("space", "local_floor")))
    assertNull(SceneLayout.fromJson(JSONObject().put("schema_version", 1).put("space", "anchor")))
    assertNull(SceneLayout.fromJson("not an object"))
    assertEquals(SceneLayout(), SceneLayout.fromJson(JSONObject()))
    val json = layout().toJson()
    json.getJSONObject("windows").getJSONObject(b).put("orientation_xyzw", org.json.JSONArray(listOf(0, 0, 0, 0)))
    json.getJSONObject("windows").put("screen_bad", JSONObject().put("mode", "sideways"))
    json.put("fullscreen_screen_id", b)
    val read = SceneLayout.fromJson(json)!!
    assertEquals(setOf(a), read.windows.keys)
    assertNull(read.fullscreen)
  }

  @Test fun pruningDropsClosedScreensAndTheirFullscreen() {
    val l = layout().fullscreenToggled(b).pruned(setOf(a))
    assertEquals(setOf(a), l.windows.keys)
    assertNull(l.fullscreen)
    assertSame(l, l.pruned(setOf(a, b)))
  }
}
