package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FistGripTest {
  /**
   * A closed left fist in hand space: wrist at the origin, fingers forward along -Z, back of the
   * hand +Y, thumb side +X. Each finger's knuckle is 8 cm ahead of the wrist, the middle and end
   * joints curl 4 cm under the palm.
   */
  private fun leftFist(): FloatArray {
    val joints = FloatArray(FistShape.JOINT_COUNT * 3)
    fun set(joint: Int, x: Float, y: Float, z: Float) {
      joints[joint * 3] = x
      joints[joint * 3 + 1] = y
      joints[joint * 3 + 2] = z
    }
    set(0, 0f, 0f, -0.045f) // palm
    set(1, 0f, 0f, 0f) // wrist
    set(2, 0.02f, -0.01f, -0.02f) // thumb
    set(3, 0.035f, -0.02f, -0.04f)
    set(4, 0.03f, -0.035f, -0.06f)
    set(FistShape.THUMB_TIP, 0.015f, -0.045f, -0.075f)
    val across = floatArrayOf(0.03f, 0.01f, -0.01f, -0.03f) // index, middle, ring, little
    for ((f, finger) in FistShape.FINGERS.withIndex()) {
      val x = across[f]
      set(finger[0], x * 0.5f, 0f, -0.01f) // metacarpal
      set(finger[1], x, 0f, -0.08f) // knuckle
      set(finger[2], x, -0.04f, -0.08f) // middle joint
      set(finger[3], x, -0.04f, -0.055f) // end joint
      set(finger[4], x, -0.02f, -0.05f) // tip
    }
    return joints
  }

  private fun transformed(joints: FloatArray, by: Pose): FloatArray {
    val out = joints.copyOf()
    for (j in 0 until FistShape.JOINT_COUNT) {
      val p = by * Vector3(joints[j * 3], joints[j * 3 + 1], joints[j * 3 + 2])
      out[j * 3] = p.x
      out[j * 3 + 1] = p.y
      out[j * 3 + 2] = p.z
    }
    return out
  }

  private fun assertVector(expected: Vector3, actual: Vector3, delta: Float = 0.0005f) {
    assertEquals(expected.x, actual.x, delta)
    assertEquals(expected.y, actual.y, delta)
    assertEquals(expected.z, actual.z, delta)
  }

  private fun assertPose(expected: Pose, actual: Pose?) {
    assertNotNull(actual)
    assertVector(expected.t, actual!!.t)
    for (axis in listOf(Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f))) {
      assertVector(expected.q * axis, actual.q * axis)
    }
  }

  @Test fun gripSitsInTheHollowWithTheGrilleOutOfTheThumbSide() {
    val grip = FistGrip.pose(leftFist())!!
    // Centred across the fist, under the palm, between the knuckles and the wrist.
    assertVector(Vector3(0f, -0.02f, -0.065f), grip.t)
    // +Y (the microphone's grille) runs little finger -> index finger; +Z points back to the wrist.
    assertVector(Vector3(1f, 0f, 0f), grip.q * Vector3(0f, 1f, 0f))
    assertVector(Vector3(0f, 0f, 1f), grip.q * Vector3(0f, 0f, 1f))
    assertVector(Vector3(0f, -1f, 0f), grip.q * Vector3(1f, 0f, 0f))
  }

  @Test fun gripMovesAndTurnsWithTheFist() {
    val fist = leftFist()
    val inHand = FistGrip.pose(fist)!!
    val poses =
      listOf(
        Pose(Vector3(0.2f, 1.1f, -0.4f), Quaternion(0f, 0f, 90f)), // wrist rolled a quarter turn
        Pose(Vector3(-0.1f, 1.4f, -0.3f), Quaternion(-70f, 30f, 0f)), // fist raised toward the mouth
        Pose(Vector3(0.05f, 0.9f, 0.1f), Quaternion(35f, -120f, 160f)),
      )
    for (hand in poses) assertPose(hand * inHand, FistGrip.pose(transformed(fist, hand)))
  }

  @Test fun fromAxesMatchesTheSdkRotation() {
    for (q in listOf(Quaternion(0f, 0f, 0f), Quaternion(20f, 70f, -35f), Quaternion(179f, -90f, 45f), Quaternion(-60f, 180f, 120f))) {
      val x = q * Vector3(1f, 0f, 0f)
      val y = q * Vector3(0f, 1f, 0f)
      val z = q * Vector3(0f, 0f, 1f)
      assertPose(Pose(Vector3(0f), q), Pose(Vector3(0f), FistGrip.fromAxes(x, y, z)))
    }
  }

  @Test fun degenerateJointsHaveNoGrip() {
    assertNull(FistGrip.pose(FloatArray(FistShape.JOINT_COUNT * 3)))
    assertNull(FistGrip.pose(FloatArray(3)))
  }
}
