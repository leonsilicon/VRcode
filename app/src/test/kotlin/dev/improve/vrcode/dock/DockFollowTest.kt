package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class DockFollowTest {
  private val eye = Vector3(0f, 1.6f, 0f)
  private fun head(yaw: Float, pitch: Float = 0f, at: Vector3 = eye) = Pose(at, Quaternion(pitch, yaw, 0f))
  private fun assertAt(expected: Pose, actual: Pose, delta: Float = 0.001f) {
    assertEquals(expected.t.x, actual.t.x, delta)
    assertEquals(expected.t.y, actual.t.y, delta)
    assertEquals(expected.t.z, actual.t.z, delta)
  }

  /** Runs [seconds] of 90 Hz frames with the head held at [pose]. */
  private fun DockFollow.hold(pose: Pose, seconds: Float): Pose {
    var last = update(pose, 1f / 90f)
    repeat((seconds * 90).toInt() - 1) { last = update(pose, 1f / 90f) }
    return last
  }

  @Test fun appearsInPlaceThenIgnoresSmallTurnsMovesAndNods() {
    val follow = DockFollow()
    assertNull(follow.pose)
    val start = follow.update(head(0f), 0f)
    assertAt(DockPlacement.pose(head(0f)), start)
    // Within the dead zone nothing moves, however long the head stays there.
    assertAt(start, follow.hold(head(12f), 3f))
    assertAt(start, follow.hold(head(-10f, pitch = 8f, at = eye + Vector3(0.05f, -0.04f, 0.05f)), 3f))
  }

  @Test fun followsALargeLookDownLikeAHomeBarAtTheBottomOfTheView() {
    val follow = DockFollow()
    val start = follow.update(head(0f), 0f)
    val down = follow.hold(head(0f, pitch = 40f), 3f)
    assertTrue(down.t.y < start.t.y - 0.05f)
    assertAt(DockPlacement.pose(head(0f, pitch = 40f)), down, 0.01f)
  }

  @Test fun easesTowardALargeTurnInsteadOfJumpingThenSettles() {
    val follow = DockFollow(timeConstantS = 0.45f)
    val start = follow.update(head(0f), 0f)
    val target = DockPlacement.pose(head(60f))
    val first = follow.update(head(60f), 1f / 90f)
    // One frame moves only a small fraction of the way.
    val moved = (first.t - start.t).length()
    assertTrue(moved > 0f && moved < (target.t - start.t).length() * 0.05f)
    // After about one time constant it is roughly two thirds there; after a few it has caught up.
    val mid = follow.hold(head(60f), 0.45f)
    val progress = (mid.t - start.t).length() / (target.t - start.t).length()
    assertTrue("progress=$progress", progress in 0.55f..0.75f)
    assertAt(target, follow.hold(head(60f), 3f), 0.01f)
    // Once settled, a small return turn inside the dead zone leaves it in place.
    val settled = follow.pose!!
    assertAt(settled, follow.hold(head(50f), 2f))
  }

  @Test fun walkingOrSittingFollowsPosition() {
    val follow = DockFollow()
    follow.update(head(0f), 0f)
    val seated = head(0f, at = eye + Vector3(0f, -0.45f, 0f))
    assertAt(DockPlacement.pose(seated), follow.hold(seated, 4f), 0.01f)
  }

  @Test fun turnsTheShortWayAcrossBehind() {
    val follow = DockFollow()
    follow.update(head(170f), 0f)
    follow.hold(head(-170f), 4f)
    assertAt(DockPlacement.pose(head(-170f)), follow.pose!!, 0.01f)
  }

  @Test fun recenterOrTeleportSnapsAndResetAppearsInPlace() {
    val follow = DockFollow()
    follow.update(head(0f), 0f)
    val far = head(90f, at = Vector3(4f, 1.6f, 3f))
    assertAt(DockPlacement.pose(far), follow.update(far, 1f / 90f))
    follow.reset()
    assertNull(follow.pose)
    assertAt(DockPlacement.pose(head(-45f)), follow.update(head(-45f), 0.5f))
  }

  @Test fun aStalledFrameDoesNotJumpAcrossTheView() {
    val follow = DockFollow(timeConstantS = 0.45f)
    val start = follow.update(head(0f), 0f)
    val target = DockPlacement.pose(head(90f))
    val after = follow.update(head(90f), 5f)
    assertTrue((after.t - start.t).length() < (target.t - start.t).length() * 0.25f)
  }
}
