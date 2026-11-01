package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgePointerTest {
  @Test fun theDotStaysUnderTheRayOverEachResizeBandIncludingRotatedWindows() {
    for (q in listOf(Quaternion(0f, 0f, 0f), Quaternion(12f, 50f, 0f))) {
      val panel = Pose(Vector3(0f, 1.5f, 2f), q)
      val direction = q * Vector3(0f, 0f, 1f)
      for ((x, y) in listOf(0.53f to 0f, -0.53f to 0f, 0f to -0.33f)) {
        val crossing = panel.t + q * Vector3(x, y, 0f)
        val origin = crossing - direction * 2f
        val hit = EdgeBand.hit(origin, direction, panel, 1f, 0.6f)!!
        val shape = EdgePointerShape.at(origin, direction, hit.distance)!!
        // It follows the actual aim, rather than snapping to the bar's centre or onto the panel.
        assertTrue((shape.dot.t - (crossing - direction * 0.008f)).length() < 1e-5f)
        val tailEnd = shape.laser.t + shape.laser.q * Vector3(0f, 0f, shape.laserSize.z / 2f)
        assertTrue((tailEnd - shape.dot.t).length() < 1e-5f)
        assertEquals(0.018f, shape.diameter, 1e-6f)
      }
    }
  }

  @Test fun noPointerIsDrawnForAnInvalidRayOrAPlaneBehindTheHand() {
    val origin = Vector3(0f, 0f, 0f)
    val forward = Vector3(0f, 0f, 1f)
    assertNull(EdgePointerShape.at(origin, forward, -1f))
    assertNull(EdgePointerShape.at(origin, forward, Float.NaN))
    assertNull(EdgePointerShape.at(origin, Vector3(0f, 0f, 0f), 1f))
  }
}
