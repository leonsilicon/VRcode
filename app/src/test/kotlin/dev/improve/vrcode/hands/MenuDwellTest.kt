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
    for (ms in 100L..1099L step 50L) assertNull(dwell.update(3, ms, along = 0f))
    assertEquals(3, dwell.hovered)
  }

  @Test fun swipingToThePillsEndRunsTheItemAtOnce() {
    val dwell = MenuDwell()
    assertNull(dwell.update(1, 100, along = 1.7f))
    assertNull(dwell.update(1, 114, along = 2.2f))
    assertEquals(0.5f + 0.014f, dwell.progress, 0.001f)
    assertEquals(1, dwell.update(1, 128, along = 2.7f))
    assertEquals(1f, dwell.swiped, 0f)
    assertNull(dwell.update(1, 142, along = 3f))
  }

  @Test fun aPartSwipeSpeedsUpTheSecond() {
    val dwell = MenuDwell()
    dwell.update(0, 100, along = 0f)
    assertNull(dwell.update(0, 400, along = 0.4f))
    assertEquals(0.7f, dwell.progress, 0.001f)
    assertNull(dwell.update(0, 699, along = 0.4f))
    assertEquals(0, dwell.update(0, 700, along = 0.4f))
  }

  @Test fun movingBackInwardTakesTheSwipeAway() {
    val dwell = MenuDwell()
    dwell.update(2, 100, along = 1f)
    dwell.update(2, 110, along = 1.9f)
    assertEquals(0.91f, dwell.progress, 0.001f)
    dwell.update(2, 120, along = 1f)
    assertEquals(0.02f, dwell.progress, 0.001f)
    assertEquals(0f, dwell.swiped, 0f)
  }

  @Test fun theSwipeCountsFromTheInnermostPointOfTheHover() {
    val dwell = MenuDwell()
    dwell.update(0, 100, along = 1.2f)
    dwell.update(0, 101, along = 1f)
    assertNull(dwell.update(0, 102, along = 1.95f))
    assertEquals(0, dwell.update(0, 103, along = 2f))
  }

  @Test fun crossingIntoTheNextItemFarOutCannotRunIt() {
    val dwell = MenuDwell()
    dwell.update(0, 100, along = 1f)
    dwell.update(0, 110, along = 1.8f)
    // Already beyond the next item's pill end: its swipe starts here.
    assertNull(dwell.update(1, 120, along = 2.5f))
    assertNull(dwell.update(1, 130, along = 2.6f))
    assertEquals(0.11f, dwell.progress, 0.001f)
  }

  @Test fun swipingDuringAFlickerCountsNothingUntilThePinchIsBack() {
    val dwell = MenuDwell()
    dwell.update(0, 100, along = 1f)
    assertNull(dwell.update(0, 114, confirmed = false, along = 3f))
    assertNull(dwell.update(0, 128, confirmed = false, along = 3f))
    assertEquals(0f, dwell.progress, 0f)
    // Back on the pinch: the hand really is a whole swipe out.
    assertEquals(0, dwell.update(0, 142, along = 2f))
  }

  @Test fun theOtherHandsRayOnlyUsesTheClock() {
    val dwell = MenuDwell()
    dwell.update(4, 100)
    assertNull(dwell.update(4, 600))
    assertEquals(0.5f, dwell.progress, 0.001f)
    assertEquals(0f, dwell.swiped, 0f)
    // The pinching hand takes over the same item: its swipe starts from where it is.
    assertNull(dwell.update(4, 700, along = 3f))
    assertEquals(0.6f, dwell.progress, 0.001f)
    assertEquals(4, dwell.update(4, 701, along = 3.5f))
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
