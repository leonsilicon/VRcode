package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

class DockPlacementTest {
  private val eye = Vector3(0.2f, 1.6f, -0.3f)

  /** Pitch (x, positive looks down) and yaw (y) in degrees, then roll about the line of sight. */
  private fun head(pitch: Float, yaw: Float = 25f, roll: Float = 0f, at: Vector3 = eye) =
    Pose(at, Quaternion(pitch, yaw, 0f) * Quaternion(0f, 0f, roll))

  private fun assertVector(a: Vector3, b: Vector3, delta: Float = 0.0001f) {
    assertEquals(a.x, b.x, delta)
    assertEquals(a.y, b.y, delta)
    assertEquals(a.z, b.z, delta)
  }

  private fun degrees(radians: Float) = Math.toDegrees(radians.toDouble()).toFloat()

  @Test fun sitsLowInThePeripheryAtArmsReachFacingTheEyesAndLevel() {
    for (viewer in listOf(head(0f), head(0f, yaw = -140f, at = Vector3(3f, 1.2f, 2f)))) {
      val dock = DockPlacement.pose(viewer)
      val offset = dock.t - viewer.t
      assertEquals(-DockPlacement.BELOW_EYES_M, offset.y, 0.0001f)
      assertEquals(DockPlacement.FORWARD_M, Vector3(offset.x, 0f, offset.z).length(), 0.0001f)
      // Well below the centre of the view: about 42 degrees under the horizon.
      assertTrue(degrees(asin(-offset.y / offset.length())) > 40f)
      // Straight ahead in the horizontal plane.
      val forward = viewer.forward()
      assertEquals(degrees(atan2(forward.x, forward.z)), degrees(atan2(offset.x, offset.z)), 0.01f)
      // Visible face is -Z: the panel's forward axis points away from the eyes, and it never rolls.
      assertVector(offset.normalize(), dock.forward().normalize())
      assertEquals(0f, dock.right().y, 0.0001f)
    }
  }

  @Test fun pitchAndRollAreIgnored() {
    val level = DockPlacement.pose(head(0f))
    for (viewer in listOf(head(0f, roll = 30f), head(40f), head(-60f), head(30f, roll = 25f), head(-45f, roll = -35f), head(75f))) {
      assertVector(level.t, DockPlacement.pose(viewer).t)
    }
  }

  @Test fun straightUpOrDownKeepsTheFacingDirection() {
    for (pitch in listOf(-90f, 90f)) {
      assertEquals(0f, DockPlacement.yawDegrees(head(pitch, yaw = 0f)), 0.01f)
      val offset = DockPlacement.pose(head(pitch, yaw = 0f)).t - eye
      assertTrue(offset.z > 0f)
    }
  }

  @Test fun pointerIntersectionFollowsTheDockAndRejectsMissesParallelAndBackFacingRays() {
    for (viewer in listOf(head(0f), head(-30f, yaw = 90f, roll = 15f, at = Vector3(2f, 1.9f, 0.5f)))) {
      val panel = DockPlacement.pose(viewer)
      val toward = panel.t - viewer.t
      assertEquals(toward.length(), DockPlacement.hitDistance(panel, viewer.t, toward)!!, 0.0001f)
      val outside = panel.t + panel.q * Vector3(DockPlacement.WIDTH_M, 0f, 0f)
      assertNull(DockPlacement.hitDistance(panel, viewer.t, outside - viewer.t))
      assertNull(DockPlacement.hitDistance(panel, viewer.t, panel.q * Vector3(1f, 0f, 0f)))
      assertNull(DockPlacement.hitDistance(panel, viewer.t, toward * -1f))
      assertNull(DockPlacement.hitDistance(panel, panel.t + panel.forward(), panel.forward() * -1f))
      assertNull(DockPlacement.hitDistance(panel, viewer.t, Vector3(0f, 0f, 0f)))
    }
  }

  @Test fun collapsedFootprintOnlyCatchesRaysThroughTheLine() {
    val viewer = head(0f)
    val panel = DockPlacement.pose(viewer)
    val nearEdge = panel.t + panel.q * Vector3(DockPlacement.PILL_WIDTH_M, 0f, 0f)
    assertNotNull(DockPlacement.hitDistance(panel, viewer.t, nearEdge - viewer.t))
    assertNull(DockPlacement.hitDistance(panel, viewer.t, nearEdge - viewer.t, DockPlacement.PILL_WIDTH_M, DockPlacement.PILL_HEIGHT_M))
    assertNotNull(DockPlacement.hitDistance(panel, viewer.t, panel.t - viewer.t, DockPlacement.PILL_WIDTH_M, DockPlacement.PILL_HEIGHT_M))
  }

  @Test fun handDistanceMeasuresToTheRectangle() {
    val panel = DockPlacement.pose(head(0f))
    assertEquals(0f, DockPlacement.distanceTo(panel, panel.t), 0.0001f)
    assertEquals(0.05f, DockPlacement.distanceTo(panel, panel.t - panel.forward() * 0.05f), 0.0001f)
    val beside = panel.t + panel.q * Vector3(DockPlacement.WIDTH_M / 2f + 0.08f, 0f, 0f)
    assertEquals(0.08f, DockPlacement.distanceTo(panel, beside), 0.0001f)
    assertEquals(DockPlacement.WIDTH_M / 2f + 0.08f - DockPlacement.PILL_WIDTH_M / 2f,
      DockPlacement.distanceTo(panel, beside, DockPlacement.PILL_WIDTH_M, DockPlacement.PILL_HEIGHT_M), 0.0001f)
  }
}
