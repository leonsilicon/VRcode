package dev.improve.vrcode.pie

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PieGeometryTest {
  @Test fun aSwipeEndsAtTheFarEndOfTheExpandedPill() {
    // Top and bottom: straight out through the pill's height.
    assertEquals(PIE_ITEM_RADIUS + PIE_ITEM_HEIGHT / 2, pieItemFarEnd(0, 8), 1e-6f)
    assertEquals(PIE_ITEM_RADIUS + PIE_ITEM_HEIGHT / 2, pieItemFarEnd(4, 8), 1e-6f)
    // Right and left: the pill slides inward to stay on the pie, so it ends at the pie's edge.
    assertEquals(0.5f, pieItemFarEnd(2, 8), 1e-6f)
    assertEquals(0.5f, pieItemFarEnd(6, 8), 1e-6f)
    // Every pill ends beyond its icon and on the pie.
    for (count in 1..8) for (i in 0 until count) {
      val end = pieItemFarEnd(i, count)
      assertTrue("item $i of $count ends at $end", end >= PIE_ITEM_RADIUS + PIE_ITEM_HEIGHT / 2 - 1e-6f && end <= 0.5f * sqrt(2f))
    }
  }
}
