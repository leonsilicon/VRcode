package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.xmux.spatial.WindowEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeBandTest {
  /** A 1 m x 0.6 m window 1 m ahead, facing a viewer at the origin. */
  private val window = Pose(Vector3(0f, 1.5f, 1f), Quaternion(0f, 0f, 0f))
  private val width = 1f
  private val height = 0.6f

  /** Straight ahead at local ([x], [y]) of [pose], from half a metre in front of it. */
  private fun hitAt(x: Float, y: Float, pose: Pose = window): EdgeHit? {
    val forward = pose.q.times(Vector3(0f, 0f, 1f))
    val origin = pose.t + pose.q.times(Vector3(x, y, 0f)) - forward * 0.5f
    return EdgeBand.hit(origin, forward, pose, width, height)
  }

  @Test fun theBandJustPastEachEdgeResizesFromThatEdge() {
    assertEquals(WindowEdge.Right, hitAt(0.52f, 0f)?.edge)
    assertEquals(WindowEdge.Left, hitAt(-0.53f, 0.1f)?.edge)
    assertEquals(WindowEdge.Top, hitAt(0f, 0.32f)?.edge)
    assertEquals(WindowEdge.Bottom, hitAt(0.1f, -0.33f)?.edge)
    assertEquals(0.5f, hitAt(0.52f, 0f)!!.distance, 1e-4f)
  }

  @Test fun theWindowItselfAndFarAwayAreNotEdges() {
    // On the window: its own input (title bar, terminal) handles the ray.
    assertNull(hitAt(0.3f, 0f))
    assertNull(hitAt(0.49f, 0.29f))
    // Past the band.
    assertNull(hitAt(0.6f, 0f))
    assertNull(hitAt(0f, -0.4f))
  }

  @Test fun cornersAreLeftToTheScaleHandles() {
    assertNull(hitAt(0.52f, 0.25f))
    assertNull(hitAt(-0.45f, 0.32f))
    assertNotNull(hitAt(0.52f, 0.22f))
  }

  @Test fun edgesFollowTheWindowsOwnAxes() {
    val turned = Pose(Vector3(1f, 1.4f, 0.2f), Quaternion(10f, 70f, 0f))
    val right = hitAt(0.52f, 0f, turned)
    assertEquals(WindowEdge.Right, right?.edge)
    assertEquals(0.52f, right!!.along, 1e-4f)
    val top = hitAt(-0.2f, 0.31f, turned)
    assertEquals(WindowEdge.Top, top?.edge)
    assertEquals(0.31f, top!!.along, 1e-4f)
  }

  @Test fun draggingOutwardGrowsAndInwardShrinks() {
    assertEquals(1.1f, EdgeBand.extent(WindowEdge.Right, 1f, 0.52f, 0.62f), 1e-5f)
    assertEquals(0.9f, EdgeBand.extent(WindowEdge.Right, 1f, 0.52f, 0.42f), 1e-5f)
    assertEquals(1.2f, EdgeBand.extent(WindowEdge.Left, 1f, -0.52f, -0.72f), 1e-5f)
    assertEquals(0.7f, EdgeBand.extent(WindowEdge.Top, 0.6f, 0.31f, 0.41f), 1e-5f)
    assertEquals(0.5f, EdgeBand.extent(WindowEdge.Bottom, 0.6f, -0.31f, -0.21f), 1e-5f)
  }

  @Test fun raysThatNeverReachTheWindowsPlaneMiss() {
    val origin = Vector3(0.52f, 1.5f, 0.5f)
    assertNull(EdgeBand.hit(origin, Vector3(0f, 0f, -1f), window, width, height))
    assertNull(EdgeBand.hit(origin, Vector3(1f, 0f, 0f), window, width, height))
    assertNull(EdgeBand.hit(origin, Vector3(0f, 0f, 0f), window, width, height))
  }
}
