package dev.improve.vrcode

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class WindowMotionTest {
  private val head = Pose(Vector3(0.2f, 1.6f, -0.3f), Quaternion(0f, 25f, 0f))
  private val offset = Pose(Vector3(-0.35f, -0.1f, 1.4f), Quaternion(0f, -10f, 0f))
  private val world = head * offset
  private val movedHead = Pose(Vector3(2f, 1.9f, 0.5f), Quaternion(20f, 90f, 15f))

  private fun assertPose(expected: Pose, actual: Pose?) {
    assertNotNull(actual)
    fun assertVector(a: Vector3, b: Vector3) {
      assertEquals(a.x, b.x, 0.0001f)
      assertEquals(a.y, b.y, 0.0001f)
      assertEquals(a.z, b.z, 0.0001f)
    }
    assertVector(expected.t, actual!!.t)
    // Compare the rotated axes rather than an acos(dot) angle, which amplifies float noise.
    for (axis in listOf(Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f))) {
      assertVector(expected.q * axis, actual.q * axis)
    }
  }

  @Test fun fixedWindowsNeverWriteAPoseWhenTheHeadMoves() {
    val motion = WindowMotion(WindowMode.Fixed)
    assertNull(motion.poseForFrame(head, world))
    assertNull(motion.poseForFrame(movedHead, world))
  }

  @Test fun followKeepsItsViewportOffsetThroughTranslationYawPitchAndRoll() {
    val motion = WindowMotion(WindowMode.Follow)
    assertPose(world, motion.poseForFrame(head, world))
    assertPose(movedHead * offset, motion.poseForFrame(movedHead, world))
  }

  @Test fun switchingToFollowDoesNotJumpAndSwitchingBackFreezesTheCurrentPose() {
    val motion = WindowMotion(WindowMode.Fixed)
    motion.setMode(WindowMode.Follow, head, world)
    assertPose(world, motion.poseForFrame(head, world))
    val followed = motion.poseForFrame(movedHead, world)!!
    motion.setMode(WindowMode.Fixed, movedHead, followed)
    assertNull(motion.poseForFrame(head, followed))
    motion.setMode(WindowMode.Follow, movedHead, followed)
    assertPose(followed, motion.poseForFrame(movedHead, followed))
    assertPose(world, motion.poseForFrame(head, followed))
  }

  @Test fun explicitMovesRebaseFollowingWithoutChangingOtherWindows() {
    val first = WindowMotion(WindowMode.Follow)
    val second = WindowMotion(WindowMode.Follow)
    first.poseForFrame(head, world)
    second.poseForFrame(head, world)
    val arranged = Pose(Vector3(1f, 1f, 2f), Quaternion(0f, 30f, 0f))
    first.moved(head, arranged)
    assertPose(arranged, first.poseForFrame(head, arranged))
    assertPose(movedHead * head.inverse() * arranged, first.poseForFrame(movedHead, arranged))
    assertPose(movedHead * offset, second.poseForFrame(movedHead, world))
  }

  @Test fun followWaitsForTrackingAndUsesTheFirstValidHeadPose() {
    val motion = WindowMotion(WindowMode.Fixed)
    motion.setMode(WindowMode.Follow, null, world)
    assertPose(world, motion.poseForFrame(head, world))
    assertPose(movedHead * offset, motion.poseForFrame(movedHead, world))
  }
}
