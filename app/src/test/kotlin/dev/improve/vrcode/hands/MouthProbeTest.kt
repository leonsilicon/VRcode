package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MouthProbeTest {
  private val level = Pose(Vector3(0.2f, 1.2f, -0.5f), Quaternion())

  /** The head looking [degrees] down: OpenXR forward (-Z) tips toward -Y. */
  private fun lookingDown(degrees: Float): Pose {
    val a = Math.toRadians(degrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return Pose(level.t, FistGrip.fromAxes(Vector3(1f, 0f, 0f), Vector3(0f, c, -s), Vector3(0f, s, c)))
  }

  /** A fist whose grille sits exactly at [head]'s mouth, the grille direction straight up. */
  private fun fistAtMouth(head: Pose): Pose {
    val mouth = head.t + head.q.times(MouthProbe.MOUTH_IN_HEAD)
    return Pose(mouth - Vector3(0f, MouthProbe.GRILLE_M, 0f), Quaternion())
  }

  @Test
  fun aFistHeldToTheMouthReadsZeroDistanceAndAimsAtIt() {
    val r = MouthProbe.measure(fistAtMouth(level), level)
    assertEquals(0f, r.mouthM, 1e-4f)
    assertEquals(0f, r.aimDegrees, 0.5f)
    assertEquals(MouthProbe.MOUTH_IN_HEAD.y, r.grilleInHead.y, 1e-4f)
    assertEquals(MouthProbe.MOUTH_IN_HEAD.z, r.grilleInHead.z, 1e-4f)
  }

  @Test
  fun theMouthMovesWithTheHeadNotTheWorld() {
    val down = lookingDown(40f)
    // Held where the mouth was before the head tipped down: no longer at the mouth.
    assertTrue(MouthProbe.measure(fistAtMouth(level), down).mouthM > 0.03f)
    val r = MouthProbe.measure(fistAtMouth(down), down)
    assertEquals(0f, r.mouthM, 1e-4f)
    // In head space the mouth stays below and slightly in front of the eyes.
    assertEquals(MouthProbe.MOUTH_IN_HEAD.y, r.grilleInHead.y, 1e-4f)
    assertEquals(MouthProbe.MOUTH_IN_HEAD.z, r.grilleInHead.z, 1e-4f)
  }

  @Test
  fun aFistOnTheDeskIsFarFromTheMouth() {
    val desk = Pose(level.t + Vector3(0.15f, -0.45f, -0.3f), Quaternion())
    assertTrue(MouthProbe.measure(desk, level).mouthM > 0.35f)
  }

  @Test
  fun aFistPointingSidewaysDoesNotAimAtTheMouth() {
    val mouth = level.t + MouthProbe.MOUTH_IN_HEAD
    // Grille direction along +X (sideways), fist just below the mouth.
    val sideways = Pose(mouth - Vector3(0f, 0.08f, 0f), FistGrip.fromAxes(Vector3(0f, -1f, 0f), Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f)))
    assertEquals(90f, MouthProbe.measure(sideways, level).aimDegrees, 1f)
  }
}
