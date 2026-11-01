package dev.improve.vrcode

import androidx.compose.ui.geometry.Offset
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class TitleBarDragTest {
  /** A 1000 x 600 px panel at 1 mm per pixel. */
  private val centre = Offset(500f, 300f)
  private val mpp = 0.001f
  private val head = Vector3(0f, 1.6f, 0f)

  @Test fun draggingRightAndDownMovesTheWindowWithoutChangingItsDepthOrRotation() {
    val pose = Pose(Vector3(1f, 2f, 3f), Quaternion(0f, 0f, 0f))
    val moved = titleBarDragPose(pose, Offset(100f, 20f), Offset(180f, 40f), centre, 0.001f, 0.002f, head = null)
    assertPosition(moved, 1.08f, 1.96f, 3f)
    assertEquals(pose.q, moved.q)
  }

  @Test fun draggingAnAngledWindowUsesItsLocalAxesAndResizedScale() {
    val pose = Pose(Vector3(1f, 2f, 3f), Quaternion(0f, 90f, 0f))
    val moved = titleBarDragPose(pose, Offset(100f, 20f), Offset(200f, 10f), centre, 0.002f, 0.004f, head = null)
    assertPosition(moved, 1f, 2.04f, 2.8f)
    assertEquals(pose.q, moved.q)
  }

  @Test fun panelMotionDoesNotPullTheGrabPointBackOrAccumulateMovement() {
    val anchor = Offset(100f, 20f)
    val first = titleBarDragPose(Pose(Vector3(0f, 1f, 2f), Quaternion(0f, 0f, 0f)),
      anchor, Offset(150f, 20f), centre, 0.001f, 0.001f, head = null)
    // Once the native panel catches up, a stationary ray is back over the original grab point.
    val stationary = titleBarDragPose(first, anchor, anchor, centre, 0.001f, 0.001f, head = null)
    assertPosition(stationary, 0.05f, 1f, 2f)
    // A further 30 px of pointer motion moves another 3 cm, independent of the first event.
    val next = titleBarDragPose(stationary, anchor, Offset(130f, 20f), centre, 0.001f, 0.001f, head = null)
    assertPosition(next, 0.08f, 1f, 2f)
  }

  @Test fun draggingSidewaysTurnsTheWindowToFaceTheUserAtTheSameDistance() {
    // Grabbed left of centre: sliding across a facing plane would pull this one closer.
    val anchor = Offset(200f, 20f)
    val start = facingWindow(1.2f)
    val ray = sweep(start, anchor, origin = head, axis = Vector3(0f, 1f, 0f), degrees = 70)
    assertFacesHead(ray.pose)
    assertOnRay(grabPoint(ray.pose, anchor), head, ray.direction)
    assertEquals(1.2f, (ray.pose.t - head).length(), 0.0005f)
  }

  @Test fun draggingUpTiltsTheWindowTowardTheUserWithoutRolling() {
    val anchor = Offset(800f, 20f)
    val ray = sweep(facingWindow(1.2f), anchor, origin = head, axis = Vector3(-1f, 0f, 0f), degrees = 40)
    assertTrue("the window rose", ray.pose.t.y > head.y + 0.5f)
    assertFacesHead(ray.pose)
    assertOnRay(grabPoint(ray.pose, anchor), head, ray.direction)
    assertEquals(1.2f, (ray.pose.t - head).length(), 0.0005f)
  }

  @Test fun theGrabPointSettlesOnAHandRayAwayFromTheHead() {
    val anchor = Offset(300f, 30f)
    val hand = Vector3(0.25f, 1.3f, 0.2f)
    var ray = sweep(facingWindow(1.2f), anchor, origin = hand, axis = Vector3(0f, 1f, 0f), degrees = -50)
    // The hand stops; the panel catches up to the stationary ray over the following frames.
    repeat(20) {
      ray = Ray(titleBarDragPose(ray.pose, anchor, hitPixel(ray.pose, hand, ray.direction), centre, mpp, mpp, head),
        ray.direction)
    }
    assertFacesHead(ray.pose)
    assertOnRay(grabPoint(ray.pose, anchor), hand, ray.direction)
    assertEquals(1.2f, (ray.pose.t - head).length(), 0.0005f)
  }

  @Test fun aStationaryPointerLeavesAFacingWindowInPlace() {
    val pose = facingWindow(1.2f)
    val anchor = Offset(150f, 25f)
    val held = titleBarDragPose(pose, anchor, anchor, centre, mpp, mpp, head)
    assertPose(pose, held)
  }

  @Test fun aWideWindowLeftAngledAfterWalkingTurnsAboutTheGrabPointToFaceTheUser() {
    // 4 x 2.4 m, the largest resize, grabbed near its top-left corner.
    val wide = Offset(2000f, 1200f)
    val anchor = Offset(100f, 20f)
    val pose = Pose(head + Vector3(0f, 0f, 2.1f), Quaternion(0f, 40f, 0f))
    val grabbed = grabPoint(pose, anchor, wide)
    val turned = titleBarDragPose(pose, anchor, anchor, wide, mpp, mpp, head)
    assertFacesHead(turned)
    assertVector(grabbed, grabPoint(turned, anchor, wide))
  }

  @Test fun pushingPlacesTheGrabPointOnTheHandRayAtTheNewDistanceFacingTheUser() {
    val anchor = Offset(300f, 30f)
    val hand = Vector3(0.2f, 1.35f, 0.3f)
    val direction = Vector3(-0.1f, 0.25f, 1f)
    val pushed = titleBarDepthPose(anchor, centre, mpp, mpp, head, hand, direction, radius = 2.5f)!!
    assertFacesHead(pushed)
    assertOnRay(grabPoint(pushed, anchor), hand, direction)
    assertEquals(2.5f, (grabPoint(pushed, anchor) - head).length(), 0.0005f)
    assertVector(grabPoint(pushed, anchor), titleBarGrabPoint(pushed, anchor, centre, mpp, mpp))
  }

  @Test fun pullingAWindowNoCloserThanTheHandHasNoPlacement() {
    val hand = Vector3(0f, 1.4f, 0.5f)
    assertEquals(null, titleBarDepthPose(Offset(500f, 20f), centre, mpp, mpp, head, hand, Vector3(0f, 0f, 1f), radius = 0.3f))
  }

  @Test fun depthHoldsUntilTheHandReachesThenScalesExponentially() {
    val forward = Vector3(0f, 0f, 1f)
    val depth = DragDepth(1.5f, Vector3(0f, -0.3f, 0.35f))
    // Sideways sweeps and a small reach are an ordinary drag.
    assertEquals(1.5f, depth.radius(Vector3(0.2f, -0.3f, 0.35f), forward, 0.45f, 4f), 0f)
    assertEquals(1.5f, depth.radius(Vector3(0.2f, -0.3f, 0.37f), forward, 0.45f, 4f), 0f)
    // Engaging at 3 cm starts from the current distance rather than jumping.
    assertEquals(1.5f, depth.radius(Vector3(0.2f, -0.3f, 0.38f), forward, 0.45f, 4f), 0.0001f)
    // A further 10 cm of reach moves the window e^0.35 further; pulling back the same returns it.
    assertEquals(1.5f * exp(0.35f), depth.radius(Vector3(0.2f, -0.3f, 0.48f), forward, 0.45f, 4f), 0.0005f)
    assertEquals(1.5f, depth.radius(Vector3(0.2f, -0.3f, 0.38f), forward, 0.45f, 4f), 0.0005f)
    assertEquals(1.5f * exp(-0.35f), depth.radius(Vector3(0.2f, -0.3f, 0.28f), forward, 0.45f, 4f), 0.0005f)
  }

  @Test fun reachIsMeasuredAlongTheRayAsItTurns() {
    val depth = DragDepth(1.5f, Vector3(0f, 0f, 0.4f))
    // A quarter arc of the arm at constant reach, the ray pointing outward from the body as it turns.
    var radius = 1.5f
    for (degree in 1..90) {
      val angle = Math.toRadians(degree.toDouble()).toFloat()
      val outward = Vector3(sin(angle), 0f, cos(angle))
      radius = depth.radius(outward * 0.4f, outward, 0.45f, 4f)
    }
    assertEquals(1.5f, radius, 0f)
  }

  @Test fun pastALimitPullingBackMovesTheWindowAtOnce() {
    val forward = Vector3(0f, 0f, 1f)
    val depth = DragDepth(3.5f, Vector3(0f, 0f, 0.3f))
    assertEquals(4f, depth.radius(Vector3(0f, 0f, 0.7f), forward, 0.45f, 4f), 0f)
    // Overshooting by 30 cm is not stored: 1 cm back already brings it nearer.
    assertEquals(4f * exp(-0.035f), depth.radius(Vector3(0f, 0f, 0.69f), forward, 0.45f, 4f), 0.0005f)
  }

  @Test fun aWindowAlreadyBeyondTheLimitsIsNotSnappedOnGrab() {
    val forward = Vector3(0f, 0f, 1f)
    val depth = DragDepth(5f, Vector3(0f, 0f, 0.3f))
    assertEquals(5f, depth.radius(Vector3(0f, 0f, 0.34f), forward, 0.45f, 4f), 0.0005f)
    assertTrue(depth.radius(Vector3(0f, 0f, 0.3f), forward, 0.45f, 4f) < 5f)
  }

  private class Ray(val pose: Pose, val direction: Vector3)

  /** Turns a ray from [origin] through the grab point by [degrees], one degree per drag event. */
  private fun sweep(start: Pose, anchor: Offset, origin: Vector3, axis: Vector3, degrees: Int): Ray {
    val initial = grabPoint(start, anchor) - origin
    var pose = start
    var direction = initial
    val sign = if (degrees < 0) -1 else 1
    for (angle in 1..degrees * sign) {
      direction = rotate(initial, axis, (angle * sign).toFloat())
      pose = titleBarDragPose(pose, anchor, hitPixel(pose, origin, direction), centre, mpp, mpp, head)
    }
    return Ray(pose, direction)
  }

  private fun facingWindow(distance: Float) = Pose(head + Vector3(0f, 0f, distance), Quaternion(0f, 0f, 0f))

  private fun local(anchor: Offset, origin: Offset) =
    Vector3((anchor.x - origin.x) * mpp, (origin.y - anchor.y) * mpp, 0f)

  private fun grabPoint(pose: Pose, anchor: Offset, origin: Offset = centre) = pose.t + pose.q.times(local(anchor, origin))

  /** Where a ray meets the panel, in panel pixels, as the pointer reports it. */
  private fun hitPixel(pose: Pose, origin: Vector3, direction: Vector3): Offset {
    val normal = pose.q.times(Vector3(0f, 0f, 1f))
    val hit = origin + direction * (normal.dot(pose.t - origin) / normal.dot(direction))
    val onPanel = pose.q.inverse().times(hit - pose.t)
    return Offset(centre.x + onPanel.x / mpp, centre.y - onPanel.y / mpp)
  }

  private fun rotate(v: Vector3, axis: Vector3, degrees: Float): Vector3 {
    val k = axis.normalize()
    val radians = Math.toRadians(degrees.toDouble()).toFloat()
    return v * cos(radians) + k.cross(v) * sin(radians) + k * (k.dot(v) * (1f - cos(radians)))
  }

  /** Visible face (local -Z) toward the head, and upright: no roll. */
  private fun assertFacesHead(pose: Pose) {
    assertVector((pose.t - head).normalize(), pose.q.times(Vector3(0f, 0f, 1f)))
    assertEquals("no roll", 0f, pose.q.times(Vector3(1f, 0f, 0f)).y, 0.0005f)
    assertTrue("upright", pose.q.times(Vector3(0f, 1f, 0f)).y > 0f)
  }

  private fun assertOnRay(point: Vector3, origin: Vector3, direction: Vector3) {
    assertEquals("distance from the ray", 0f, (point - origin).cross(direction.normalize()).length(), 0.001f)
  }

  private fun assertPose(expected: Pose, actual: Pose) {
    assertVector(expected.t, actual.t)
    for (axis in listOf(Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f))) {
      assertVector(expected.q.times(axis), actual.q.times(axis))
    }
  }

  private fun assertVector(expected: Vector3, actual: Vector3) {
    assertEquals(expected.x, actual.x, 0.0005f)
    assertEquals(expected.y, actual.y, 0.0005f)
    assertEquals(expected.z, actual.z, 0.0005f)
  }

  private fun assertPosition(pose: Pose, x: Float, y: Float, z: Float) {
    assertEquals(x, pose.t.x, 0.00001f)
    assertEquals(y, pose.t.y, 0.00001f)
    assertEquals(z, pose.t.z, 0.00001f)
  }
}
