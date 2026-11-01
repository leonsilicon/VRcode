package dev.improve.vrcode

import androidx.compose.ui.geometry.Offset
import dev.improve.vrcode.TitleBarDragGate.HandState
import dev.improve.xmux.client.ScreenId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleBarDragGateTest {
  private val window = ScreenId(1)
  private val other = ScreenId(2)
  private val anchor = Offset(100f, 20f)
  private val logs = mutableListOf<String>()
  private val gate = TitleBarDragGate { logs += it }

  private fun right(pressed: Boolean, header: ScreenId? = null) = HandState(left = false, pressed = pressed, header = header)
  private fun left(pressed: Boolean, header: ScreenId? = null) = HandState(left = true, pressed = pressed, header = header)

  @Test fun pointingAtATitleBarWithoutAPressNeverMovesTheWindow() {
    // The header's gesture reports a press (the SDK's phantom select) while no pinch is held.
    gate.update(listOf(right(pressed = false)))
    assertNull(gate.drag(window, anchor, Offset(120f, 20f)))
    assertNull(gate.drag(window, anchor, Offset(200f, 40f)))
    assertNull(gate.holder(window))
    assertEquals(1, logs.count { "ignored" in it })
  }

  @Test fun aPinchStartedOnTheTitleBarDragsUntilItIsReleased() {
    gate.update(listOf(right(pressed = true, header = window)))
    assertEquals(anchor, gate.drag(window, anchor, Offset(120f, 20f)))
    assertEquals(anchor, gate.drag(window, anchor, Offset(160f, 30f)))
    assertEquals(false, gate.holder(window))
    gate.update(listOf(right(pressed = false)))
    // The header's gesture carries on without a release: the window stays where it was let go.
    assertNull(gate.drag(window, anchor, Offset(200f, 40f)))
    assertNull(gate.holder(window))
  }

  @Test fun losingTheHandEndsItsDrag() {
    gate.update(listOf(right(pressed = true, header = window)))
    assertEquals(anchor, gate.drag(window, anchor, Offset(120f, 20f)))
    gate.update(emptyList())
    assertNull(gate.drag(window, anchor, Offset(140f, 20f)))
  }

  @Test fun aPinchThatStartedElsewhereDoesNotDragATitleBarItSweepsOnto() {
    gate.update(listOf(right(pressed = true, header = null)))
    gate.update(listOf(right(pressed = true, header = window)))
    assertNull(gate.drag(window, anchor, Offset(120f, 20f)))
    // Nor does a pinch on one window's title bar drag another window.
    gate.update(listOf(right(pressed = false)))
    gate.update(listOf(right(pressed = true, header = other)))
    assertNull(gate.drag(window, Offset(300f, 20f), Offset(320f, 20f)))
  }

  @Test fun aPinchThatTakesOverAPhantomGestureGrabsWhereItPoints() {
    // A gesture the header began with no pinch held...
    assertNull(gate.drag(window, anchor, Offset(400f, 25f)))
    // ...is taken over by a pinch started on the title bar, grabbed where the pointer is then,
    // so the window does not jump by the phantom grab point's distance.
    gate.update(listOf(right(pressed = true, header = window)))
    val grab = Offset(410f, 25f)
    assertEquals(grab, gate.drag(window, anchor, grab))
    assertEquals(grab, gate.drag(window, anchor, Offset(450f, 30f)))
  }

  @Test fun aNewPinchAfterReleaseOnTheSameGestureIsGrabbedAfresh() {
    gate.update(listOf(right(pressed = true, header = window)))
    assertEquals(anchor, gate.drag(window, anchor, Offset(150f, 20f)))
    gate.update(listOf(right(pressed = false)))
    assertNull(gate.drag(window, anchor, Offset(300f, 20f)))
    gate.update(listOf(right(pressed = true, header = window)))
    assertEquals(Offset(310f, 20f), gate.drag(window, anchor, Offset(310f, 20f)))
  }

  @Test fun twoHandsDragTwoWindowsAtOnce() {
    val otherAnchor = Offset(50f, 10f)
    gate.update(listOf(right(pressed = true, header = window), left(pressed = true, header = other)))
    assertEquals(anchor, gate.drag(window, anchor, Offset(120f, 20f)))
    assertEquals(otherAnchor, gate.drag(other, otherAnchor, Offset(60f, 10f)))
    assertEquals(anchor, gate.drag(window, anchor, Offset(130f, 20f)))
    assertEquals(false, gate.holder(window))
    assertEquals(true, gate.holder(other))
    assertEquals(2, logs.count { "held by" in it })
  }
}
