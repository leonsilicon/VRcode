package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class AheadPoseTest {
  private fun flat(v: Vector3) = Vector3(v.x, 0f, v.z)

  @Test fun aNoticeWithoutAWindowSitsLevelAheadOfTheHeadingEvenWhileLookingDownAtThePalm() {
    // Level, looking down at the palm (either sign of pitch), and with the head rolled.
    for ((pitch, roll) in listOf(0f to 0f, 55f to 0f, -55f to 0f, 40f to 25f)) {
      val head = Pose(Vector3(1.5f, 1.7f, -0.4f), Quaternion(pitch, 125f, roll))
      val pose = aheadPose(head)
      val offset = pose.t - head.t
      assertEquals(AHEAD_DISTANCE_M, flat(offset).length(), 0.0001f)
      assertEquals(-AHEAD_DROP_M, offset.y, 0.0001f)
      assertEquals(1f, flat(offset).normalize().dot(flat(head.forward()).normalize()), 0.001f)
      // Upright and facing the user: its forward (away from the visible face) points away from them.
      assertEquals(1f, pose.forward().normalize().dot(flat(offset).normalize()), 0.0001f)
      assertEquals(0f, pose.forward().y, 0.0001f)
    }
  }

  @Test fun lookingStraightDownStillPlacesItAtEyeLevelInFront() {
    val head = Pose(Vector3(0f, 1.6f, 0f), Quaternion(89.9f, 30f, 0f))
    val offset = aheadPose(head).t - head.t
    assertEquals(AHEAD_DISTANCE_M, flat(offset).length(), 0.0001f)
    assertEquals(-AHEAD_DROP_M, offset.y, 0.0001f)
  }
}
