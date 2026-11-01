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

  @Test fun sitsAtTheBottomOfTheViewAtArmsReachFacingTheEyesWithoutRoll() {
    for (viewer in listOf(head(0f), head(0f, yaw = -140f, at = Vector3(3f, 1.2f, 2f)), head(20f, yaw = 60f))) {
      val bar = DockPlacement.pose(viewer)
      val offset = bar.t - viewer.t
      assertEquals(DockPlacement.DISTANCE_M, offset.length(), 0.0001f)
      // BELOW_GAZE_DEG below where the user looks, like an iPhone's home bar at the bottom of the screen.
      val gaze = degrees(asin(viewer.forward().normalize().y))
      assertEquals(gaze - DockPlacement.BELOW_GAZE_DEG, degrees(asin(offset.y / offset.length())), 0.01f)
      // Straight ahead in the horizontal plane.
      val forward = viewer.forward()
      assertEquals(degrees(atan2(forward.x, forward.z)), degrees(atan2(offset.x, offset.z)), 0.01f)
      // Visible face is -Z: the panel's forward axis points away from the eyes, and it never rolls.
      assertVector(offset.normalize(), bar.forward().normalize())
      assertEquals(0f, bar.right().y, 0.0001f)
    }
  }

  @Test fun followsPitchButNotRollAndStaysWithinReach() {
    val level = DockPlacement.pose(head(0f))
    for (viewer in listOf(head(0f, roll = 30f), head(0f, roll = -35f))) assertVector(level.t, DockPlacement.pose(viewer).t)
    // Looking down lowers the bar with the view; very steep gazes are clamped.
    assertTrue(DockPlacement.pose(head(30f)).t.y < level.t.y)
    assertEquals(DockPlacement.pose(head(85f)).t.y, DockPlacement.pose(head(89f)).t.y, 0.0001f)
    assertTrue(DockPlacement.pose(head(-80f)).t.y < eye.y) // looking up never lifts it above the eyes
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
      assertEquals(toward.length(), DockPlacement.hitDistance(panel, viewer.t, toward, W, H)!!, 0.0001f)
      val outside = panel.t + panel.q * Vector3(DockPlacement.WIDTH_M, 0f, 0f)
      assertNull(DockPlacement.hitDistance(panel, viewer.t, outside - viewer.t, W, H))
      assertNull(DockPlacement.hitDistance(panel, viewer.t, panel.q * Vector3(1f, 0f, 0f), W, H))
      assertNull(DockPlacement.hitDistance(panel, viewer.t, toward * -1f, W, H))
      assertNull(DockPlacement.hitDistance(panel, panel.t + panel.forward(), panel.forward() * -1f, W, H))
      assertNull(DockPlacement.hitDistance(panel, viewer.t, Vector3(0f, 0f, 0f), W, H))
    }
  }

  @Test fun theBarCatchesOnlyRaysThroughItAndTheBodySitsAboveIt() {
    val viewer = head(0f)
    val bar = DockPlacement.pose(viewer)
    val beside = bar.t + bar.q * Vector3(DockPlacement.BAR_HIT_WIDTH_M, 0f, 0f)
    assertNull(DockPlacement.hitDistance(bar, viewer.t, beside - viewer.t, DockPlacement.BAR_HIT_WIDTH_M, DockPlacement.BAR_HIT_HEIGHT_M))
    assertNotNull(DockPlacement.hitDistance(bar, viewer.t, bar.t - viewer.t, DockPlacement.BAR_HIT_WIDTH_M, DockPlacement.BAR_HIT_HEIGHT_M))
    val body = DockPlacement.bodyPose(bar)
    val row = bar.t + bar.q * Vector3(0f, DockPlacement.ROW_Y_M, 0f)
    assertNotNull(DockPlacement.hitDistance(body, viewer.t, row - viewer.t, DockPlacement.BODY_WIDTH_M, DockPlacement.BODY_TOP_M - DockPlacement.BODY_BOTTOM_M))
  }

  @Test fun handDistanceMeasuresToTheRectangle() {
    val panel = DockPlacement.pose(head(0f))
    assertEquals(0f, DockPlacement.distanceTo(panel, panel.t, W, H), 0.0001f)
    assertEquals(0.05f, DockPlacement.distanceTo(panel, panel.t - panel.forward() * 0.05f, W, H), 0.0001f)
    val beside = panel.t + panel.q * Vector3(W / 2f + 0.08f, 0f, 0f)
    assertEquals(0.08f, DockPlacement.distanceTo(panel, beside, W, H), 0.0001f)
  }

  private companion object {
    const val W = DockPlacement.WIDTH_M
    const val H = DockPlacement.HEIGHT_M
  }
}
