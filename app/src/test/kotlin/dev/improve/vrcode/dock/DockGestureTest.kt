package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.dock.DockGesture.Event
import dev.improve.vrcode.dock.DockGesture.Part
import dev.improve.vrcode.dock.DockGesture.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockGestureTest {
  // The bar 50 cm ahead of the origin, facing back toward it: local X = world X, local Y = world Y,
  // local Z = world Z - 0.5 (away from the user).
  private val bar = Pose(Vector3(0f, 0f, 0.5f), Quaternion.lookRotation(Vector3(0f, 0f, 1f)))
  private val options = DockLayout.optionXs(5) // -0.12, -0.06, 0, 0.06, 0.12
  private val row = DockPlacement.ROW_Y_M
  private val grab = DockPlacement.GRAB_Y_M
  private var now = 0L

  private fun touch(g: DockGesture, x: Float, y: Float, z: Float, hand: Int = 1): Event {
    now += 16
    val tips = MutableList<Vector3?>(2) { null }
    tips[hand] = Vector3(x, y, 0.5f + z)
    return g.update(bar, tips, listOf(null, null), options, now)
  }

  /** A fingertip approaching from in front, then reaching the plane at ([x], [y]). */
  private fun poke(g: DockGesture, x: Float, y: Float): Event {
    touch(g, x, y, -0.03f)
    return touch(g, x, y, 0f)
  }

  private fun reveal(g: DockGesture) {
    poke(g, 0f, 0f)
    touch(g, 0f, 0.015f, 0f)
    assertEquals(Event.Revealed, touch(g, 0f, 0.032f, 0f))
    touch(g, 0f, 0.032f, -0.05f) // the hand pulls back
  }

  @Test
  fun optionsSitInARowCentredOnTheBar() {
    assertEquals(-0.12f, options[0], 1e-6f)
    assertEquals(0f, options[2], 1e-6f)
    assertEquals(0.12f, options[4], 1e-6f)
    val many = DockLayout.optionXs(20)
    assertEquals(DockLayout.MAX_SPAN_M, many.last() - many.first() + DockLayout.spacing(20), 1e-5f)
  }

  @Test
  fun theBarBulgesAsAFingertipApproachesIt() {
    val g = DockGesture()
    touch(g, 0f, 0f, -0.2f)
    assertEquals(0f, g.hover, 1e-6f)
    touch(g, 0.01f, 0.005f, -0.03f)
    assertTrue(g.hover in 0.4f..0.6f)
    touch(g, 0.01f, 0.005f, 0f)
    assertEquals(1f, g.hover, 1e-6f)
  }

  @Test
  fun swipingUpFromTheBarRaisesTheDockWithoutPressingAnOption() {
    val g = DockGesture()
    poke(g, 0f, 0f)
    assertEquals(Part.Bar, g.pressing)
    touch(g, 0f, 0.015f, 0f)
    assertTrue(g.swipe in 0.4f..0.6f)
    assertEquals(Event.Revealed, touch(g, 0f, 0.032f, 0f))
    assertTrue(g.revealed)
    // The finger is still on the plane right below the row: that is not a new press.
    assertEquals(Event.None, touch(g, 0f, row, 0f))
    assertNull(g.pressing)
  }

  @Test
  fun pressingAnOptionSlidingAndFlickingUpActivatesItAndLowersTheDock() {
    val g = DockGesture()
    reveal(g)
    poke(g, 0f, row)
    assertEquals(Part.Option, g.pressing)
    assertEquals(2, g.highlighted)
    touch(g, 0.04f, row, 0f)
    touch(g, 0.06f, row + 0.005f, 0f)
    assertEquals(3, g.highlighted)
    touch(g, 0.06f, row + 0.02f, -0.01f)
    assertTrue(g.lift > 0.4f)
    assertEquals(Event.Activated(3), touch(g, 0.06f, row + 0.045f, -0.02f))
    assertFalse(g.revealed)
  }

  @Test
  fun pullingBackFromAnOptionCancelsThePressButKeepsTheDockUp() {
    val g = DockGesture()
    reveal(g)
    poke(g, 0f, row)
    assertEquals(Event.None, touch(g, 0f, row + 0.01f, -0.09f))
    assertNull(g.pressing)
    assertTrue(g.revealed)
  }

  @Test
  fun swipingDownFromTheTopGrabberLowersTheDock() {
    val g = DockGesture()
    reveal(g)
    touch(g, 0f, grab, -0.03f)
    assertTrue(g.hover > 0.4f) // the grabber bulges too
    touch(g, 0f, grab, 0f)
    assertEquals(Part.Grabber, g.pressing)
    touch(g, 0f, grab - 0.015f, 0f)
    assertTrue(g.swipe in 0.4f..0.6f)
    assertEquals(Event.Dismissed, touch(g, 0f, grab - 0.035f, 0f))
    assertFalse(g.revealed)
  }

  @Test
  fun theRaisedDockLowersItselfWhenLeftAlone() {
    val g = DockGesture()
    reveal(g)
    now += DockGesture.IDLE_MS + 100
    assertEquals(Event.Dismissed, g.update(bar, listOf(null, null), listOf(null, null), options, now))
  }

  @Test
  fun handsBelowTheDockOrBehindItNeverPress() {
    val g = DockGesture()
    poke(g, 0f, -0.08f) // typing hands sit well below the bar
    touch(g, 0f, 0f, 0.03f)
    touch(g, 0f, 0f, 0.01f) // from behind the plane
    poke(g, 0.2f, 0f) // beyond the bar's ends
    assertNull(g.pressing)
    assertFalse(g.revealed)
  }

  @Test
  fun aPinchedPointerRaisesTheDockAndActivatesAnOptionByAimingUp() {
    val g = DockGesture()
    val origin = Vector3(0f, 0.25f, -0.2f) // 70 cm from the bar
    fun aim(y: Float, x: Float = 0f, pinched: Boolean): Event {
      now += 16
      return g.update(bar, listOf(null, null), listOf(Pointer(origin, Vector3(x, y, 0.5f) - origin, pinched), null), options, now)
    }
    aim(0f, pinched = false)
    assertTrue(g.hover > 0f)
    aim(0f, pinched = true)
    assertEquals(Event.Revealed, aim(0.035f, pinched = true))
    aim(row, pinched = false)
    aim(row, x = -0.06f, pinched = true)
    assertEquals(1, g.highlighted)
    assertEquals(Event.Activated(1), aim(row + 0.045f, x = -0.06f, pinched = true))
  }

  @Test
  fun aNearRayFromATypingHandIsIgnored() {
    val g = DockGesture()
    val origin = Vector3(0f, -0.05f, 0.35f) // 15 cm away, just below
    fun aim(pinched: Boolean) {
      now += 16
      g.update(bar, listOf(null, null), listOf(Pointer(origin, Vector3(0f, 0f, 0.5f) - origin, pinched), null), options, now)
    }
    aim(false)
    aim(true)
    assertEquals(0f, g.hover, 1e-6f)
    assertNull(g.pressing)
  }

  @Test
  fun recallPlacesTheWindowAboveTheDockAndGlides() {
    val eye = Vector3(0f, 0f, 0f)
    val lowBar = Pose(Vector3(0f, -0.3f, 0.35f), Quaternion.lookRotation(Vector3(0f, -0.3f, 0.35f)))
    val target = DockRecall.targetPose(eye, lowBar, heightM = 0.5f, distanceM = 1.1f)
    assertTrue(target.t.z > 0.8f)
    // As seen from the eye, the window's bottom edge sits well above the bar.
    fun elevationDeg(y: Float, z: Float) = Math.toDegrees(kotlin.math.atan2(y, z).toDouble())
    val bottom = elevationDeg(target.t.y - 0.25f, target.t.z)
    assertEquals(elevationDeg(lowBar.t.y, lowBar.t.z) + DockRecall.CLEARANCE_DEG, bottom, 1.0)
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
