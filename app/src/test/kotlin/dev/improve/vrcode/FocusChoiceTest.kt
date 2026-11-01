package dev.improve.vrcode

import com.meta.spatial.core.Vector3
import dev.improve.xmux.client.ScreenId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusChoiceTest {
  private val eye = Vector3(0f, 1.6f, 0f)
  private val ahead = Vector3(0f, 0f, 1f)
  private val left = FocusChoice.Candidate(ScreenId(1), Vector3(-1f, 1.6f, 1f))
  private val centre = FocusChoice.Candidate(ScreenId(2), Vector3(0.1f, 1.5f, 1.2f))
  private val right = FocusChoice.Candidate(ScreenId(3), Vector3(1f, 1.6f, 1f))

  @Test fun nothingToFocusWithoutWindows() {
    assertNull(FocusChoice.pick(listOf(ScreenId(1)), emptyList(), eye, ahead))
  }

  @Test fun returnsToTheMostRecentlyFocusedWindowStillOpen() {
    // Screen 9 closed; 3 was focused before it, then 1.
    val recent = listOf(ScreenId(9), ScreenId(3), ScreenId(1))
    assertEquals(ScreenId(3), FocusChoice.pick(recent, listOf(left, centre, right), eye, ahead))
  }

  @Test fun otherwiseFocusesTheWindowNearestTheLineOfSight() {
    assertEquals(ScreenId(2), FocusChoice.pick(emptyList(), listOf(left, centre, right), eye, ahead))
    assertEquals(ScreenId(3), FocusChoice.pick(emptyList(), listOf(left, centre, right), eye, Vector3(1f, 0f, 1f)))
  }

  @Test fun fallsBackToTheFirstWindowWithoutAHeadPose() {
    assertEquals(ScreenId(1), FocusChoice.pick(emptyList(), listOf(left, centre, right), null, null))
    val unplaced = FocusChoice.Candidate(ScreenId(4), null)
    assertEquals(ScreenId(4), FocusChoice.pick(emptyList(), listOf(unplaced), eye, ahead))
  }
}
