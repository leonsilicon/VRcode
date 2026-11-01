package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.xmux.spatial.WindowEdge
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeBandTest {
  /** A 1 m x 0.6 m window 1 m ahead, facing a viewer at the origin. */
  private val window = Pose(Vector3(0f, 1.5f, 1f), Quaternion(0f, 0f, 0f))
  private val width = 1f
  private val height = 0.6f

  /** The ray from [from] metres in front of [pose] straight at its local ([x], [y]). */
  private fun rayAt(x: Float, y: Float, pose: Pose = window, from: Float = 0.5f): Pair<Vector3, Vector3> {
    val forward = pose.q.times(Vector3(0f, 0f, 1f))
    return (pose.t + pose.q.times(Vector3(x, y, 0f)) - forward * from) to forward
  }

  private fun hitAt(x: Float, y: Float, pose: Pose = window, from: Float = 0.5f): EdgeHit? {
    val (origin, direction) = rayAt(x, y, pose, from)
    return EdgeBand.hit(origin, direction, pose, width, height)
  }

  private fun nearAt(x: Float, y: Float, from: Float = 0.5f): EdgeHit? {
    val (origin, direction) = rayAt(x, y, window, from)
    return EdgeBand.nearHit(origin, direction, window, width, height)
  }

  @Test fun theBandJustPastEachEdgeResizesFromThatEdge() {
    assertEquals(WindowEdge.Right, hitAt(0.52f, 0f)?.edge)
    assertEquals(WindowEdge.Left, hitAt(-0.53f, 0.1f)?.edge)
    assertEquals(WindowEdge.Bottom, hitAt(0.1f, -0.33f)?.edge)
    assertEquals(0.5f, hitAt(0.52f, 0f)!!.distance, 1e-4f)
  }

  @Test fun theTopIsTheTitleBarsAndOnlyMovesTheWindow() {
    assertNull(hitAt(0f, 0.32f))
    assertNull(hitAt(0.2f, 0.34f))
    assertNull(hitAt(0f, 0.32f, from = 2.5f))
    assertNull(nearAt(0f, 0.39f))
    assertFalse(WindowEdge.Top in EdgeBand.RESIZABLE)
  }

  @Test fun theWindowItselfAndFarAwayAreNotEdges() {
    // On the window: its own input (title bar, terminal) handles the ray.
    assertNull(hitAt(0.3f, 0f))
    assertNull(hitAt(0.49f, 0.29f))
    // Past the band.
    assertNull(hitAt(0.6f, 0f))
    assertNull(hitAt(0f, -0.4f))
  }

  private fun aroundAt(x: Float, y: Float, from: Float = 0.5f): Float? {
    val (origin, direction) = rayAt(x, y, window, from)
    return EdgeBand.around(origin, direction, window, width, height)
  }

  @Test fun theLeftHandsBandGoesAllTheWayAroundTheWindow() {
    // Past every edge, the top and the corners included.
    for ((x, y) in listOf(0.52f to 0f, -0.53f to 0.1f, 0f to 0.32f, 0.1f to -0.33f, 0.52f to 0.32f, -0.53f to -0.33f)) {
      assertEquals("($x, $y)", 0.5f, aroundAt(x, y)!!, 1e-4f)
    }
    // Not on the window (that is a plain hit), and not past the band.
    assertNull(aroundAt(0f, 0f))
    assertNull(aroundAt(0.49f, 0.29f))
    assertNull(aroundAt(0.6f, 0f))
    assertNull(aroundAt(0.52f, 0.4f))
    // It widens with distance like the resize bands.
    assertNotNull(aroundAt(0.6f, 0.36f, from = 2.5f))
  }

  @Test fun theBandWidensWithDistanceSoFarEdgesStayEasyToHit() {
    assertEquals(EdgeBand.BAND_M, EdgeBand.bandM(0.5f), 1e-6f)
    assertEquals(0.15f, EdgeBand.bandM(2.5f), 1e-5f)
    assertEquals(EdgeBand.MAX_BAND_M, EdgeBand.bandM(20f), 1e-6f)
    // 10 cm past the right edge: too far at arm's length, on the band from 2.5 m.
    assertNull(hitAt(0.6f, 0f, from = 0.5f))
    assertEquals(WindowEdge.Right, hitAt(0.6f, 0f, from = 2.5f)?.edge)
    assertNull(hitAt(0.7f, 0f, from = 2.5f))
  }

  @Test fun nearAnEdgeReachesFurtherOutButNeverOntoTheWindow() {
    assertEquals(WindowEdge.Right, nearAt(0.59f, 0f)?.edge)
    assertEquals(WindowEdge.Bottom, nearAt(0f, -0.39f)?.edge)
    assertNull(nearAt(0.62f, 0f))
    assertNull(nearAt(0.49f, 0f))
    assertNull(nearAt(0f, -0.29f))
  }

  @Test fun cornersAreLeftToTheScaleHandles() {
    assertNull(hitAt(0.52f, 0.25f))
    assertNull(hitAt(-0.45f, -0.32f))
    assertNotNull(hitAt(0.52f, 0.22f))
  }

  @Test fun edgesFollowTheWindowsOwnAxes() {
    val turned = Pose(Vector3(1f, 1.4f, 0.2f), Quaternion(10f, 70f, 0f))
    val right = hitAt(0.52f, 0f, turned)
    assertEquals(WindowEdge.Right, right?.edge)
    assertEquals(0.52f, right!!.along, 1e-4f)
    val bottom = hitAt(-0.2f, -0.31f, turned)
    assertEquals(WindowEdge.Bottom, bottom?.edge)
    assertEquals(-0.31f, bottom!!.along, 1e-4f)
  }

  @Test fun draggingOutwardGrowsAndInwardShrinks() {
    assertEquals(1.1f, EdgeBand.extent(WindowEdge.Right, 1f, 0.52f, 0.62f), 1e-5f)
    assertEquals(0.9f, EdgeBand.extent(WindowEdge.Right, 1f, 0.52f, 0.42f), 1e-5f)
    assertEquals(1.2f, EdgeBand.extent(WindowEdge.Left, 1f, -0.52f, -0.72f), 1e-5f)
    assertEquals(0.7f, EdgeBand.extent(WindowEdge.Bottom, 0.6f, -0.31f, -0.41f), 1e-5f)
    assertEquals(0.5f, EdgeBand.extent(WindowEdge.Bottom, 0.6f, -0.31f, -0.21f), 1e-5f)
  }

  @Test fun raysThatNeverReachTheWindowsPlaneMiss() {
    val origin = Vector3(0.52f, 1.5f, 0.5f)
    assertNull(EdgeBand.hit(origin, Vector3(0f, 0f, -1f), window, width, height))
    assertNull(EdgeBand.hit(origin, Vector3(1f, 0f, 0f), window, width, height))
    assertNull(EdgeBand.hit(origin, Vector3(0f, 0f, 0f), window, width, height))
  }

  @Test fun eachBarLiesOutsideItsEdgeOnTheBandAndClearOfTheCorners() {
    for (distance in listOf(0.5f, 1f, 2.5f, 6f)) {
      for (edge in EdgeBand.RESIZABLE) {
        val bar = EdgeBand.bar(edge, width, height, distance)
        val sign = EdgeBand.sign(edge)
        val (centre, across, along, half, length) =
          if (EdgeBand.horizontal(edge)) listOf(bar.centre.x, bar.size.x, bar.centre.y, width / 2f, height)
          else listOf(bar.centre.y, bar.size.y, bar.centre.x, height / 2f, width)
        val inner = abs(centre) - across / 2f
        val outer = abs(centre) + across / 2f
        assertEquals("$edge on its own side", sign, Math.signum(centre), 0f)
        assertTrue("$edge at $distance m is outside the window ($inner)", inner > half)
        assertTrue("$edge at $distance m is on the band ($outer)", outer < half + EdgeBand.bandM(distance))
        assertEquals(0f, along, 0f)
        val barLength = if (EdgeBand.horizontal(edge)) bar.size.y else bar.size.x
        assertEquals(length - 2f * EdgeBand.CORNER_M, barLength, 1e-5f)
        assertTrue("toward the viewer", bar.centre.z < 0f)
      }
    }
    // Slim far away, still a few millimetres up close.
    assertEquals(EdgeBand.MIN_BAR_M, EdgeBand.bar(WindowEdge.Right, width, height, 0.3f).size.x, 1e-6f)
    assertEquals(0.01f, EdgeBand.bar(WindowEdge.Right, width, height, 2.5f).size.x, 1e-6f)
  }
}

class EdgeGripTest {
  private val always: (String) -> Boolean = { true }
  private val never: (String) -> Boolean = { false }

  /** A grip that has seen the hand open, as it is before any press. */
  private fun open() = EdgeGrip<String>().also { it.press(0L, pressed = false, target = null, near = always) }

  @Test fun aPressThatStartsOnAnEdgeGrabsIt() {
    val grip = open()
    assertNull(grip.press(10L, pressed = false, target = "right", near = always))
    assertEquals("right", grip.press(20L, pressed = true, target = "right", near = always))
  }

  @Test fun aPressJustAfterTheRayDriftsOffStillGrabsTheEdgeItIsNear() {
    val grip = open()
    grip.press(100L, pressed = false, target = "top", near = always)
    assertEquals("top", grip.press(100L + EdgeGrip.HOVER_GRACE_MS, pressed = true, target = null, near = always))
  }

  @Test fun aPressFarFromTheEdgeOrLongAfterLeavingItGrabsNothing() {
    val far = open()
    far.press(100L, pressed = false, target = "top", near = always)
    assertNull(far.press(120L, pressed = true, target = null, near = never))

    val late = open()
    late.press(100L, pressed = false, target = "top", near = always)
    late.press(200L, pressed = false, target = null, near = always)
    assertNull(late.press(101L + EdgeGrip.HOVER_GRACE_MS, pressed = true, target = null, near = always))
  }

  @Test fun aPressThatStartedElsewhereNeverGrabs() {
    val grip = open()
    assertNull(grip.press(10L, pressed = true, target = null, near = always))
    // Still held as the ray reaches the edge.
    assertNull(grip.press(20L, pressed = true, target = "left", near = always))
    // A hand first seen already pressing, or found again while pressing, is the same.
    assertNull(EdgeGrip<String>().press(0L, pressed = true, target = "left", near = always))
    val regained = open()
    regained.lost()
    assertNull(regained.press(30L, pressed = true, target = "left", near = always))
  }

  @Test fun aDragOutlastsAPinchThatFlickersOpenAndEndsOnARealRelease() {
    val grip = open()
    assertEquals("bottom", grip.press(0L, pressed = true, target = "bottom", near = always))
    assertTrue(grip.hold(10L, pressed = true))
    assertTrue(grip.hold(20L, pressed = false))
    assertTrue(grip.hold(20L + EdgeGrip.RELEASE_GRACE_MS - 1, pressed = false))
    assertTrue(grip.hold(200L, pressed = true))
    assertTrue(grip.hold(210L, pressed = false))
    assertFalse(grip.hold(210L + EdgeGrip.RELEASE_GRACE_MS, pressed = false))
  }

  @Test fun aLostHandEndsTheDragAfterTheSameGrace() {
    val grip = open()
    grip.press(0L, pressed = true, target = "right", near = always)
    grip.lost()
    assertTrue(grip.hold(10L, pressed = false))
    grip.lost()
    assertFalse(grip.hold(10L + EdgeGrip.RELEASE_GRACE_MS, pressed = false))
  }
}
