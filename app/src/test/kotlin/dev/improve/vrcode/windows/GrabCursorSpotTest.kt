package dev.improve.vrcode.windows

import androidx.compose.ui.geometry.Offset
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.GrabCursor
import dev.improve.vrcode.PanelScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrabCursorSpotTest {
  /** A 1000 x 600 px window, 1 mm a pixel, 1.5 m ahead of the origin. */
  private val window = Pose(Vector3(0f, 1.2f, 1.5f), Quaternion(0f, 0f, 0f))
  private val scale = PanelScale(Offset(500f, 300f), 0.001f, 0.001f)

  private fun spot(x: Float, y: Float, size: Float = 1f, pose: Pose = window) =
    GrabCursorSpot.at(pose, scale, 1000, 600, Offset(x, y), size)

  private fun assertVector(expected: Vector3, actual: Vector3) {
    assertEquals(expected.x, actual.x, 1e-5f)
    assertEquals(expected.y, actual.y, 1e-5f)
    assertEquals(expected.z, actual.z, 1e-5f)
  }

  @Test
  fun aCursorInsideTheWindowIsOnlyPaintedOnThePanel() {
    val spot = spot(500f, 300f)
    assertTrue(spot.onPanel)
    assertEquals(GrabCursor.EXTENT * 0.001f, spot.widthM, 1e-6f)
  }

  @Test
  fun aCursorThatStraddlesOrLeavesAnEdgeAlsoNeedsTheWorldCopy() {
    val half = GrabCursor.EXTENT / 2f
    assertTrue(spot(half, 300f).onPanel)
    assertFalse(spot(half - 1f, 300f).onPanel)
    assertFalse(spot(500f, 600f - half + 1f).onPanel)
    assertFalse(spot(-40f, 300f).onPanel)
    assertFalse(spot(1040f, -40f).onPanel)
    // At twice the size the same spot no longer fits.
    assertTrue(spot(half + 1f, 300f).onPanel)
    assertFalse(spot(half + 1f, 300f, size = 2f).onPanel)
  }

  @Test
  fun theWorldCopySitsAtThePixelJustInFrontOfTheWindow() {
    // 40 px past the left edge and 100 px above the centre (panel y grows downward).
    val spot = spot(-40f, 200f)
    assertVector(Vector3(-0.54f, 1.3f, 1.5f - GrabCursorSpot.LIFT_M), spot.pose.t)
    assertEquals(window.q.w, spot.pose.q.w, 1e-6f)
  }

  @Test
  fun theLiftFollowsATurnedWindowTowardItsViewer() {
    // Turned 90° about Y, the window's local -Z (its visible side) is world -X.
    val turned = Pose(window.t, Quaternion(0f, 90f, 0f))
    val spot = spot(500f, 300f, pose = turned)
    val toward = turned.q.times(Vector3(0f, 0f, -1f))
    assertVector(window.t + toward * GrabCursorSpot.LIFT_M, spot.pose.t)
    assertEquals(GrabCursorSpot.LIFT_M, (spot.pose.t - window.t).length(), 1e-6f)
  }
}
