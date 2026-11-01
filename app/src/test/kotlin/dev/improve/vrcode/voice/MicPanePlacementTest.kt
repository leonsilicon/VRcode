package dev.improve.vrcode.voice

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicPanePlacementTest {
  private val eye = Vector3(0f, 1.6f, 0f)
  private val paneHeightM = 0.19f

  /** A fist [below] the eyes, [ahead] in front and [side] to the right, holding the mic tilted [pitch] degrees from upright. */
  private fun grip(ahead: Float = 0.4f, below: Float = 0.45f, side: Float = 0.15f, pitch: Float = 0f) =
    Pose(Vector3(side, eye.y - below, ahead), Quaternion(pitch, 0f, 0f))

  @Test fun floatsCentredOverTheFistJustAboveAnUprightMic() {
    val g = grip(below = 0.6f)
    val centre = MicPanePlacement.target(g, eye, paneHeightM)
    assertEquals(g.t.x, centre.x, 1e-4f)
    assertEquals(g.t.z, centre.z, 1e-4f)
    val bottom = centre.y - paneHeightM / 2f
    assertEquals(g.t.y + MicPanePlacement.MIC_TOP_M + MicPanePlacement.GAP_M, bottom, 1e-4f)
  }

  @Test fun aTiltedMicLowersThePaneButItStillClearsTheFist() {
    val upright = MicPanePlacement.target(grip(), eye, paneHeightM)
    val tilted = MicPanePlacement.target(grip(pitch = 60f), eye, paneHeightM)
    assertTrue(tilted.y < upright.y)
    assertTrue(tilted.y - paneHeightM / 2f >= grip().t.y + MicPanePlacement.GAP_M - 1e-4f)
  }

  @Test fun aMicRaisedToTheMouthNeverPutsThePaneInTheFace() {
    val centre = MicPanePlacement.target(grip(ahead = 0.12f, below = 0.1f, side = 0f), eye, paneHeightM)
    assertTrue(centre.y <= eye.y - MicPanePlacement.BELOW_EYES_M + 1e-4f)
    val reach = Vector3(centre.x - eye.x, 0f, centre.z - eye.z).length()
    assertEquals(MicPanePlacement.MIN_REACH_M, reach, 1e-3f)
  }

  @Test fun facesTheEyes() {
    val centre = MicPanePlacement.target(grip(), eye, paneHeightM)
    val pose = MicPanePlacement.pose(centre, eye)
    val forward = pose.q.times(Vector3(0f, 0f, 1f))
    val away = (centre - eye).normalize()
    assertTrue(forward.dot(away) > 0.99f)
  }

  @Test fun aStagedPaneStaysOnlyWhileItIsInFrontOfTheEyes() {
    val centre = Vector3(0.1f, 1.3f, 0.45f)
    assertTrue(MicPanePlacement.inView(centre, Pose(eye, Quaternion(20f, 0f, 0f))))
    assertFalse(MicPanePlacement.inView(centre, Pose(eye, Quaternion(0f, 180f, 0f))))
    assertFalse(MicPanePlacement.inView(centre, Pose(eye + Vector3(0f, 0f, -2f), Quaternion(0f, 0f, 0f))))
  }

  @Test fun followAppearsInPlaceIgnoresJitterAndEasesAfterARealMove() {
    val follow = PaneFollow()
    val start = Vector3(0.1f, 1.3f, 0.4f)
    assertEquals(start, follow.update(start, 0f))
    // Tracking jitter and a hand nudged by a press stay inside the dead zone: the pane holds still.
    repeat(90) { assertEquals(start, follow.update(start + Vector3(0.012f, -0.01f, 0.005f), 1f / 90f)) }
    // A real move: it trails at first, then catches up.
    val moved = start + Vector3(0.15f, 0.05f, 0f)
    val first = follow.update(moved, 1f / 90f)
    assertTrue((moved - first).length() > 0.1f)
    var last = first
    repeat(90) { last = follow.update(moved, 1f / 90f) }
    assertTrue((moved - last).length() < 0.005f)
  }

  @Test fun aFistShowsThePaneOnlyOnceAWordIsTranscribed() {
    assertFalse(MicPanePlacement.shown(holding = true, text = "", notice = null))
    assertFalse(MicPanePlacement.shown(holding = true, text = "  ", notice = null))
    assertTrue(MicPanePlacement.shown(holding = true, text = "run", notice = null))
    // The fist opened: a typed (or finishing) pane hides; one that could not be typed stays with its reason.
    assertFalse(MicPanePlacement.shown(holding = false, text = "run the tests", notice = null))
    assertTrue(MicPanePlacement.shown(holding = false, text = "run the tests", notice = "No terminal to type into"))
  }

  @Test fun aNoticeShowsWithoutAnyWords() {
    assertTrue(MicPanePlacement.shown(holding = true, text = "", notice = "Mac speech service not connected"))
  }

  @Test fun followSnapsAfterATrackingJumpAndAfterReset() {
    val follow = PaneFollow()
    follow.update(Vector3(0f, 1.3f, 0.4f), 0f)
    val far = Vector3(1f, 1.3f, 0.4f)
    assertEquals(far, follow.update(far, 1f / 90f))
    follow.reset()
    val fresh = Vector3(-0.2f, 1.2f, 0.4f)
    assertEquals(fresh, follow.update(fresh, 1f / 90f))
  }
}
