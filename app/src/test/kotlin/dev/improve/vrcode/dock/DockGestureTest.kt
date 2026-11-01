package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.dock.DockGesture.Event
import dev.improve.vrcode.dock.DockGesture.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockGestureTest {
  // A dock 50 cm ahead of the origin, facing back toward it: local X = world X, local Y = world Y,
  // local Z = world Z - 0.5 (away from the user).
  private val dock = Pose(Vector3(0f, 0f, 0.5f), Quaternion.lookRotation(Vector3(0f, 0f, 1f)))
  private val options = DockLayout.optionXs(5) // -0.12, -0.06, 0, 0.06, 0.12
  private var now = 0L

  private fun tip(x: Float, y: Float, z: Float) = Vector3(x, y, 0.5f + z)

  private fun touch(gesture: DockGesture, x: Float, y: Float, z: Float, hand: Int = 1): Event {
    now += 16
    val tips = MutableList<Vector3?>(2) { null }
    tips[hand] = tip(x, y, z)
    return gesture.update(dock, tips, listOf(null, null), options, now)
  }

  private fun press(gesture: DockGesture, x: Float = 0f): Event {
    touch(gesture, x, 0f, -0.03f)
    return touch(gesture, x, 0f, 0f)
  }

  @Test
  fun optionsSitInARowCentredOnTheLine() {
    val xs = DockLayout.optionXs(5)
    assertEquals(-0.12f, xs[0], 1e-6f)
    assertEquals(0f, xs[2], 1e-6f)
    assertEquals(0.12f, xs[4], 1e-6f)
    // Many windows squeeze into the maximum span.
    val many = DockLayout.optionXs(20)
    assertEquals(DockLayout.MAX_SPAN_M, many.last() - many.first() + DockLayout.spacing(20), 1e-5f)
  }

  @Test
  fun aFingertipPressingTheLineThenFlickingUpActivatesTheHighlightedOption() {
    val g = DockGesture()
    assertEquals(Event.Pressed, press(g))
    assertEquals(2, g.highlighted)
    // Slide right along the dock to the fourth option, then flick up.
    assertEquals(Event.None, touch(g, 0.04f, 0f, 0f))
    assertEquals(Event.None, touch(g, 0.06f, 0.005f, 0f))
    assertEquals(3, g.highlighted)
    assertEquals(Event.None, touch(g, 0.06f, 0.02f, -0.01f))
    assertTrue(g.lift > 0.4f)
    assertEquals(Event.Activated(3), touch(g, 0.06f, 0.045f, -0.02f))
    assertFalse(g.pressed)
  }

  @Test
  fun pullingTheFingerBackWithoutAFlickCancels() {
    val g = DockGesture()
    press(g)
    assertEquals(Event.Cancelled, touch(g, 0f, 0.01f, -0.09f))
  }

  @Test
  fun movingDownCancels() {
    val g = DockGesture()
    press(g)
    assertEquals(Event.Cancelled, touch(g, 0f, -0.06f, 0f))
  }

  @Test
  fun aHandBelowTheDockOrBehindItNeverPresses() {
    val g = DockGesture()
    // Typing hands sit well below the line.
    touch(g, 0f, -0.08f, -0.03f)
    assertEquals(Event.None, touch(g, 0f, -0.08f, 0f))
    // A fingertip coming from behind the plane is not a press.
    touch(g, 0f, 0f, 0.03f)
    assertEquals(Event.None, touch(g, 0f, 0f, 0.01f))
    // Off the line's ends.
    touch(g, 0.2f, 0f, -0.03f)
    assertEquals(Event.None, touch(g, 0.2f, 0f, 0f))
    assertFalse(g.pressed)
  }

  @Test
  fun losingTheFingertipBrieflyIsTolerated() {
    val g = DockGesture()
    press(g)
    now += 200
    assertEquals(Event.None, g.update(dock, listOf(null, null), listOf(null, null), options, now))
    assertTrue(g.pressed)
    now += 300
    assertEquals(Event.Cancelled, g.update(dock, listOf(null, null), listOf(null, null), options, now))
  }

  @Test
  fun aPinchedPointerRayPressesFromADistanceAndFlicksByAimingUp() {
    val g = DockGesture()
    val origin = Vector3(0f, 0.25f, -0.2f) // 70 cm from the dock
    fun aim(y: Float, pinched: Boolean): Event {
      now += 16
      val target = Vector3(0f, y, 0.5f)
      return g.update(dock, listOf(null, null), listOf(Pointer(origin, target - origin, pinched), null), options, now)
    }
    aim(0f, pinched = false)
    assertEquals(Event.Pressed, aim(0f, pinched = true))
    assertEquals(DockGesture.Source.Ray, g.source)
    assertEquals(Event.Activated(2), aim(0.05f, pinched = true))
  }

  @Test
  fun releasingThePinchWithoutAFlickCancels() {
    val g = DockGesture()
    val origin = Vector3(0f, 0.25f, -0.2f)
    fun aim(pinched: Boolean): Event {
      now += 16
      return g.update(dock, listOf(null, null), listOf(null, Pointer(origin, Vector3(0f, 0f, 0.5f) - origin, pinched)), options, now)
    }
    aim(false)
    aim(true)
    assertEquals(Event.Cancelled, aim(false))
  }

  @Test
  fun aNearRayFromATypingHandDoesNotPress() {
    val g = DockGesture()
    val origin = Vector3(0f, -0.05f, 0.35f) // 15 cm away, just below
    fun aim(pinched: Boolean): Event {
      now += 16
      return g.update(dock, listOf(null, null), listOf(Pointer(origin, Vector3(0f, 0f, 0.5f) - origin, pinched), null), options, now)
    }
    aim(false)
    assertEquals(Event.None, aim(true))
  }

  @Test
  fun recallPlacesTheWindowAboveTheDockAndGlides() {
    val eye = Vector3(0f, 0f, 0f)
    val lowDock = Pose(Vector3(0f, -0.4f, 0.45f), Quaternion.lookRotation(Vector3(0f, -0.4f, 0.45f)))
    val target = DockRecall.targetPose(eye, lowDock, heightM = 0.5f, distanceM = 1.1f)
    // In the dock's direction, about 1.1 m away, and well above the dock.
    assertTrue(target.t.z > 0.9f)
    assertTrue(target.t.y - 0.25f > lowDock.t.y + 0.1f)
    val recall = DockRecall(durationMs = 100)
    val start = Pose(Vector3(2f, 0f, 0f), Quaternion.lookRotation(Vector3(2f, 0f, 0f)))
    recall.start(7, start, target, nowMs = 0)
    val mid = recall.tick(50)!!
    assertEquals(7, mid.first)
    assertTrue(mid.second.t.x in 0.1f..1.9f)
    val end = recall.tick(120)!!
    assertEquals(target.t.x, end.second.t.x, 1e-5f)
    assertFalse(recall.active)
  }
}
