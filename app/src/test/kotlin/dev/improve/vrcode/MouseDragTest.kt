package dev.improve.vrcode

import dev.improve.xmux.client.MouseAction
import dev.improve.xmux.client.MouseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MouseDragTest {
  private val events = mutableListOf<Pair<String, MouseEvent>>()
  private val drag = MouseDrag<String> { target, event -> events += target to event }
  private fun cell(row: Int, col: Int) = MouseDrag.Cell(row, col)

  @Test fun pinchHoldAndReleaseSendsACompleteDragToTheCapturedTerminal() {
    drag.press("original pane", cell(2, 3))
    drag.move(cell(2, 9))
    drag.move(cell(4, 12))
    drag.release(cell(4, 13))
    assertEquals(listOf(
      "original pane" to MouseEvent(MouseAction.Press, 0, 2, 3),
      "original pane" to MouseEvent(MouseAction.Move, 0, 2, 9),
      "original pane" to MouseEvent(MouseAction.Move, 0, 4, 12),
      "original pane" to MouseEvent(MouseAction.Move, 0, 4, 13),
      "original pane" to MouseEvent(MouseAction.Release, 0, 4, 13),
    ), events)
    assertNull(drag.target)
  }

  @Test fun aQuickPinchClicksWithoutMovingOrAddingAnExtraRelease() {
    drag.press("pane", cell(0, 0))
    drag.release(cell(0, 0))
    drag.release()
    assertEquals(listOf(MouseAction.Press, MouseAction.Release), events.map { it.second.action })
  }

  @Test fun aQuickDragMovesToTheReleaseCellEvenWithoutAnIntermediateMoveEvent() {
    drag.press("pane", cell(0, 0))
    drag.release(cell(0, 5))
    assertEquals(listOf(
      "pane" to MouseEvent(MouseAction.Press, 0, 0, 0),
      "pane" to MouseEvent(MouseAction.Move, 0, 0, 5),
      "pane" to MouseEvent(MouseAction.Release, 0, 0, 5),
    ), events)
  }

  @Test fun cancellingOnTrackingLossOrPauseReleasesAtTheLastKnownCellOnce() {
    drag.press("pane", cell(1, 2))
    drag.move(cell(3, 4))
    drag.release()
    drag.release()
    drag.move(cell(7, 8))
    assertEquals(3, events.size)
    assertEquals("pane" to MouseEvent(MouseAction.Release, 0, 3, 4), events.last())
  }

  @Test fun stationaryHandDoesNotFloodTheBridgeWithIdenticalMoves() {
    drag.press("pane", cell(1, 2))
    repeat(90) { drag.move(cell(1, 2)) }
    drag.move(cell(1, 3))
    repeat(90) { drag.move(cell(1, 3)) }
    assertEquals(2, events.size)
  }

  @Test fun aNewGestureReleasesTheOldPaneBeforePressingTheNewOne() {
    drag.press("first", cell(1, 2))
    drag.press("second", cell(5, 6))
    drag.release()
    assertEquals(listOf(
      "first" to MouseEvent(MouseAction.Press, 0, 1, 2),
      "first" to MouseEvent(MouseAction.Release, 0, 1, 2),
      "second" to MouseEvent(MouseAction.Press, 0, 5, 6),
      "second" to MouseEvent(MouseAction.Release, 0, 5, 6),
    ), events)
  }
}
