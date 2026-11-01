package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathClearingTest {
  private val eye = Vector3(0f, 1.6f, 0f)
  private val ahead = Vector3(0f, -0.4f, 1f) // looking down at the palm: only the heading counts
  private val halfWidth = 0.5f

  /** A window [radius] metres from the eye at [degrees] from straight ahead (positive toward +X), facing the eye. */
  private fun at(degrees: Float, radius: Float = 1.5f, height: Float = 1.5f): Pose {
    val a = Math.toRadians(degrees.toDouble()).toFloat()
    val position = Vector3(sin(a) * radius, height, cos(a) * radius)
    return Pose(position, Quaternion.lookRotation(Vector3(position.x, 0f, position.z)))
  }

  private fun degrees(pose: Pose): Float {
    val o = pose.t - eye
    return Math.toDegrees(atan2(o.x, o.z).toDouble()).toFloat()
  }

  private fun radius(pose: Pose): Float {
    val o = pose.t - eye
    return sqrt(o.x * o.x + o.z * o.z)
  }

  /** Degrees from straight ahead to the window's inner edge, as the eye sees it. */
  private fun innerEdge(pose: Pose) =
    abs(degrees(pose)) - Math.toDegrees(atan(halfWidth / radius(pose)).toDouble()).toFloat()

  private fun aside(vararg windows: Pair<String, Pose>): Map<String, Pose> =
    PathClearing.aside(eye, ahead, windows.map { (key, pose) -> PathClearing.Window(key, pose, halfWidth) })

  @Test fun windowsInFrontPartToEitherSideUntilTheMiddleIsClear() {
    val moved = aside("left" to at(-25f), "middle-left" to at(-3f), "middle-right" to at(4f), "right" to at(30f))
    assertEquals(setOf("left", "middle-left", "middle-right", "right"), moved.keys)
    assertTrue(degrees(moved.getValue("middle-right")) > 0f)
    assertTrue(degrees(moved.getValue("middle-left")) < 0f)
    // The window nearest the middle on each side ends with its inner edge right at the clear angle.
    assertEquals(PathClearing.CLEAR_DEG, innerEdge(moved.getValue("middle-right")), 0.01f)
    assertEquals(PathClearing.CLEAR_DEG, innerEdge(moved.getValue("middle-left")), 0.01f)
    // A side swings together: its windows keep their spacing and order.
    assertEquals(26f, degrees(moved.getValue("right")) - degrees(moved.getValue("middle-right")), 0.01f)
    assertEquals(22f, degrees(moved.getValue("middle-left")) - degrees(moved.getValue("left")), 0.01f)
  }

  @Test fun eachWindowKeepsItsDistanceHeightAndFacesTheUser() {
    val start = at(10f, radius = 2.2f, height = 1.9f)
    val end = aside("one" to start).getValue("one")
    assertEquals(radius(start), radius(end), 1e-4f)
    assertEquals(start.t.y, end.t.y, 1e-5f)
    // Still facing the eye: its +Z points straight away from it, horizontally.
    val facing = end.q.times(Vector3(0f, 0f, 1f))
    val away = (end.t - eye).let { Vector3(it.x, 0f, it.z) }.normalize()
    assertEquals(1f, facing.dot(away), 1e-4f)
  }

  @Test fun windowsAlreadyClearBesideOrBehindStayPut() {
    assertTrue(aside("clear" to at(75f), "behind" to at(170f), "beside" to at(-95f)).isEmpty())
    // Only the side with something in the way moves.
    val moved = aside("in the way" to at(-10f), "clear" to at(80f))
    assertEquals(setOf("in the way"), moved.keys)
  }

  @Test fun theHeadingIsTheHeadsYawNotItsTilt() {
    val turned = PathClearing.aside(eye, Vector3(1f, -0.6f, 0f), listOf(PathClearing.Window("ahead", at(90f), halfWidth)))
    assertFalse(turned.isEmpty())
    val toward = turned.getValue("ahead")
    assertEquals(90f + PathClearing.CLEAR_DEG + Math.toDegrees(atan(halfWidth / 1.5f).toDouble()).toFloat(), abs(degrees(toward)), 0.01f)
  }

  @Test fun aGlideSwingsAroundTheUserAndLandsExactly() {
    val from = at(5f)
    val to = PathClearing.swung(from, eye, Math.toRadians(60.0).toFloat())
    val glide = WindowGlide<String>(durationMs = 400L)
    glide.start("w", from, to, eye, nowMs = 1000L)
    val mid = glide.tick(1200L).getValue("w")
    // Halfway (smoothstep is symmetric): on the arc, not cutting the chord closer to the user.
    assertEquals(radius(from), radius(mid), 1e-4f)
    assertEquals(35f, degrees(mid), 0.05f)
    assertTrue(glide.active)
    val last = glide.tick(1400L).getValue("w")
    assertEquals(0f, (last.t - to.t).length(), 1e-6f)
    assertFalse(glide.active)
    assertTrue(glide.tick(1500L).isEmpty())
  }

  @Test fun aCancelledGlideStopsWhereItIs() {
    val glide = WindowGlide<String>()
    glide.start("a", at(0f), at(60f), eye, 0L)
    glide.start("b", at(0f), at(-60f), eye, 0L)
    glide.cancel("a")
    assertEquals(setOf("b"), glide.tick(10L).keys)
  }
}
