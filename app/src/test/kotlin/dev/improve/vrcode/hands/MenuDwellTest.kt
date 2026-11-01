package dev.improve.vrcode.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MenuDwellTest {
  @Test fun aFullSecondOfHoverFiresOnce() {
    val dwell = MenuDwell()
    assertNull(dwell.update(0, 100))
    assertNull(dwell.update(0, 600))
    assertEquals(0.5f, dwell.progress, 0.001f)
    assertNull(dwell.update(0, 1099))
    assertEquals(0, dwell.update(0, 1100))
    assertEquals(1f, dwell.progress, 0f)
    assertNull(dwell.update(0, 2200))
    assertNull(dwell.update(1, 3300))
  }

  @Test fun switchingItemsStartsANewSecond() {
    val dwell = MenuDwell()
    dwell.update(0, 100)
    dwell.update(0, 1000)
    assertNull(dwell.update(1, 1050))
    assertEquals(0f, dwell.progress, 0f)
    assertNull(dwell.update(1, 2049))
    assertEquals(1, dwell.update(1, 2050))
  }

  @Test fun leavingOrLosingThePinchAtTheDeadlineNeverLaunches() {
    val dwell = MenuDwell()
    dwell.update(2, 100)
    dwell.update(2, 1099)
    assertNull(dwell.update(null, 1100))
    assertNull(dwell.hovered)
    assertEquals(0f, dwell.progress, 0f)
    assertNull(dwell.update(2, 1200))
    assertNull(dwell.update(2, 2199))
    assertEquals(2, dwell.update(2, 2200))
  }

  @Test fun closingAndReopeningCannotKeepProgressOrRepeatAnAction() {
    val dwell = MenuDwell()
    dwell.update(0, 100)
    dwell.update(0, 1000)
    dwell.reset()
    assertNull(dwell.update(0, 2000))
    assertEquals(0, dwell.update(0, 3000))
    dwell.reset()
    assertNull(dwell.update(0, 5000))
    assertNull(dwell.update(0, 5999))
    assertEquals(0, dwell.update(0, 6000))
  }

  @Test fun reachingAnItemAloneRunsNothing() {
    val dwell = MenuDwell()
    for (ms in 100L..1099L step 50L) assertNull(dwell.update(3, ms))
    assertEquals(3, dwell.hovered)
  }

  @Test fun reachingTheLabelPillRunsTheHoveredItemAtOnceAndOnlyOnce() {
    val dwell = MenuDwell()
    assertNull(dwell.update(1, 100))
    assertNull(dwell.update(1, 250))
    assertEquals(1, dwell.finish(1))
    assertEquals(1f, dwell.progress, 0f)
    assertNull(dwell.finish(1))
    assertNull(dwell.update(1, 2000))
  }

  @Test fun theLabelPillOnlyRunsTheItemWhoseLabelIsShowing() {
    val dwell = MenuDwell()
    // Nothing hovered yet: arriving on a label's spot in one jump shows the label first.
    assertNull(dwell.finish(2))
    assertNull(dwell.hovered)
    dwell.update(0, 100)
    assertNull(dwell.finish(2))
    assertEquals(0, dwell.hovered)
    // Once its label shows, it can run.
    dwell.update(2, 120)
    assertEquals(2, dwell.finish(2))
  }

  @Test fun theLabelPillCannotRunTwiceAcrossAReopenWithoutAFreshHover() {
    val dwell = MenuDwell()
    dwell.update(0, 100)
    assertEquals(0, dwell.finish(0))
    dwell.reset()
    assertNull(dwell.finish(0))
    dwell.update(0, 3000)
    assertEquals(0, dwell.finish(0))
  }

  @Test fun trackingOrPinchFlickerPausesAndCannotFinishAtTheDeadline() {
    val dwell = MenuDwell()
    dwell.update(0, 100)
    dwell.update(0, 1099)
    assertNull(dwell.update(0, 1100, confirmed = false))
    assertNull(dwell.update(0, 1150, confirmed = false))
    assertNull(dwell.update(0, 1200))
    assertEquals(0.999f, dwell.progress, 0.001f)
    assertEquals(0, dwell.update(0, 1201))
    dwell.reset()
    assertNull(dwell.hovered)
    assertEquals(0f, dwell.progress, 0f)
  }
}
