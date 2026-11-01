package dev.improve.vrcode

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeLaserShapeTest {
  /** An aim at shoulder height pointing straight ahead (+Z). */
  private val aim = Pose(Vector3(0.2f, 1.2f, 0.3f), Quaternion(0f, 0f, 0f))

  private fun assertVector(expected: Vector3, actual: Vector3) {
    assertEquals(expected.x, actual.x, 1e-5f)
    assertEquals(expected.y, actual.y, 1e-5f)
    assertEquals(expected.z, actual.z, 1e-5f)
  }

  @Test
  fun handLaserStartsPastTheAimPointAtFullLength() {
    val shape = FreeLaserShape.at(aim, hand = true)!!
    assertVector(Vector3(0.2f, 1.2f, 0.4f), shape.position)
    assertVector(Vector3(FreeLaserShape.HALF_WIDTH_M, FreeLaserShape.HALF_WIDTH_M, FreeLaserShape.MAX_LENGTH_M), shape.scale)
  }

  @Test
  fun controllerLaserStartsAtTheTip() {
    val shape = FreeLaserShape.at(aim, hand = false)!!
    assertVector(aim.t, shape.position)
    assertEquals(FreeLaserShape.MAX_LENGTH_M, shape.scale.z, 1e-5f)
  }

  @Test
  fun followsTheAimRotation() {
    // Turned 90° about Y, the ray points along +X and the box keeps the aim's rotation.
    val turned = Pose(aim.t, Quaternion(0f, 90f, 0f))
    val shape = FreeLaserShape.at(turned, hand = true)!!
    val forward = turned.forward()
    assertVector(aim.t + forward * FreeLaserShape.HAND_START_M, shape.position)
    assertVector(forward, shape.rotation.times(Vector3(0f, 0f, 1f)))
  }

  @Test
  fun stopsShortOfANearSurface() {
    val shape = FreeLaserShape.at(aim, hand = true, surface = 0.3f)!!
    assertEquals(0.3f - FreeLaserShape.HAND_START_M - FreeLaserShape.SURFACE_M, shape.scale.z, 1e-5f)
    // A far surface leaves the full length.
    assertEquals(FreeLaserShape.MAX_LENGTH_M, FreeLaserShape.at(aim, hand = true, surface = 2f)!!.scale.z, 1e-5f)
  }

  @Test
  fun noLaserWhenTheSurfaceIsWithinReach() {
    assertNull(FreeLaserShape.at(aim, hand = true, surface = 0.12f))
    assertNull(FreeLaserShape.at(aim, hand = false, surface = 0.04f))
  }
}
