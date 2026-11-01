package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAlignmentTest {
  /** Scene from LOCAL after converting OpenXR's right-handed coordinates to Spatial's left-handed ones. */
  private val sceneFromLocal = Pose(Vector3(0.3f, 1.6f, -0.2f), Quaternion(0f, 35f, 0f))
  // These fixtures are in Spatial's left-handed LOCAL coordinates. Raw OpenXR inputs are built
  // below from reflected basis vectors, independently of the production quaternion conversion.
  private val headInLocal = Pose(Vector3(0.1f, 0.05f, -0.3f), Quaternion(15f, -40f, 5f))
  private val handInLocal = Pose(Vector3(-0.15f, -0.35f, -0.45f), Quaternion(-50f, 20f, 80f))

  private fun reflect(v: Vector3) = Vector3(v.x, v.y, -v.z)

  private fun openXr(pose: Pose) = Pose(
    reflect(pose.t),
    FistGrip.fromAxes(
      reflect(pose.q * Vector3(1f, 0f, 0f)),
      reflect(pose.q * Vector3(0f, 1f, 0f)),
      reflect(pose.q * Vector3(0f, 0f, -1f)),
    ),
  )

  private fun assertPose(expected: Pose, actual: Pose?, delta: Float = 0.0005f) {
    assertNotNull(actual)
    fun assertVector(a: Vector3, b: Vector3) {
      assertEquals(a.x, b.x, delta)
      assertEquals(a.y, b.y, delta)
      assertEquals(a.z, b.z, delta)
    }
    assertVector(expected.t, actual!!.t)
    for (axis in listOf(Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f))) {
      assertVector(expected.q * axis, actual.q * axis)
    }
  }

  @Test fun unknownUntilTheHeadIsSeenInBothSpaces() {
    assertNull(SceneAlignment().toScene(handInLocal))
  }

  @Test fun mapsLocalPosesIntoTheScene() {
    val alignment = SceneAlignment()
    alignment.update(openXr(headInLocal), sceneFromLocal * headInLocal)
    assertPose(sceneFromLocal * handInLocal, alignment.toScene(openXr(handInLocal)))
    // The same offset from any other head pose.
    val moved = Pose(Vector3(-0.4f, 0.1f, 0.6f), Quaternion(-30f, 150f, -10f))
    alignment.update(openXr(moved), sceneFromLocal * moved)
    assertPose(sceneFromLocal * handInLocal, alignment.toScene(openXr(handInLocal)))
  }

  @Test fun aFistInFrontOfTheHeadStaysInFront() {
    val alignment = SceneAlignment()
    val head = Pose(Vector3(0f, 1.6f, 0f))
    alignment.update(Pose(), head)
    val grip = alignment.toScene(Pose(Vector3(-0.15f, -0.3f, -0.4f)))!!
    assertPose(Pose(Vector3(-0.15f, 1.3f, 0.4f)), grip)
    assertTrue((grip.t - head.t).dot(head.forward()) > 0f)
  }

  @Test fun aRaisedMicrophonesGrillePointsBackTowardTheHead() {
    val alignment = SceneAlignment()
    alignment.update(Pose(), Pose(Vector3(0f, 1.6f, 0f)))
    // In OpenXR, tilting +Y 90 degrees around X points the grille toward the viewer (+Z).
    val grip = alignment.toScene(Pose(Vector3(-0.15f, -0.2f, -0.3f), Quaternion(90f, 0f, 0f)))!!
    val grille = grip * Vector3(0f, 0.144f, 0f)
    assertEquals(-0.15f, grille.x, 0.0005f)
    assertEquals(1.4f, grille.y, 0.0005f)
    assertEquals(0.156f, grille.z, 0.0005f)
  }

  @Test fun smoothsFrameTimingNoiseButSnapsToARecenter() {
    val alignment = SceneAlignment()
    alignment.update(openXr(headInLocal), sceneFromLocal * headInLocal)
    // The scene's head pose from a slightly different time: 1 cm off. The offset barely moves.
    val late = sceneFromLocal * headInLocal
    alignment.update(openXr(headInLocal), Pose(late.t + Vector3(0.01f, 0f, 0f), late.q))
    assertEquals(sceneFromLocal.t.x + 0.001f, alignment.offset!!.t.x, 0.0002f)
    // A recenter moves the spaces apart by more than the snap distance: applied at once.
    val recentered = Pose(Vector3(1.3f, 1.6f, 0.4f), Quaternion(0f, -80f, 0f))
    alignment.update(openXr(headInLocal), recentered * headInLocal)
    assertPose(recentered * handInLocal, alignment.toScene(openXr(handInLocal)))
  }
}
