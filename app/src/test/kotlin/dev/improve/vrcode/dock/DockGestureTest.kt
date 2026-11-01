package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.DockShelf
import dev.improve.vrcode.dock.DockGesture.Event
import dev.improve.vrcode.dock.DockGesture.Hand
import dev.improve.vrcode.dock.DockGesture.Phase
import dev.improve.vrcode.dock.DockGesture.Pointer
import dev.improve.vrcode.dock.DockGesture.Surface
import dev.improve.vrcode.dock.DockGesture.Touch
import dev.improve.vrcode.hands.viewToScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockGestureTest {
  // The bar 50 cm ahead of the origin, facing back toward it: local X = world X, local Y = world Y,
  // local Z = world Z - 0.5 (away from the user).
  private val bar = Pose(Vector3(0f, 0f, 0.5f), Quaternion.lookRotation(Vector3(0f, 0f, 1f)))
  private val options = DockLayout.optionXs(5) // -0.12, -0.06, 0, 0.06, 0.12
  private val row = DockPlacement.ROW_Y_M
  private val pull = DockPlacement.TRAVEL_M
  private var now = 0L

  /** One frame with the right hand at [tip] / [pinch], aiming [pointer], pinching if [pressed]. */
  private fun frame(
    g: DockGesture, vararg touches: Touch, tip: Vector3? = null, pinch: Vector3? = null, pointer: Pointer? = null, pressed: Boolean = false,
  ): Event {
    now += 16
    val hand = if (tip == null && pinch == null && pointer == null && !pressed) null else Hand(tip, pinch, pointer, pressed)
    return g.update(bar, listOf(null, hand), touches.toList(), options, now)
  }

  private fun down(surface: Surface, x: Float, y: Float) = Touch(surface, Phase.Down, x, y)

  private fun move(surface: Surface, x: Float, y: Float) = Touch(surface, Phase.Move, x, y)

  private fun up(surface: Surface, x: Float, y: Float) = Touch(surface, Phase.Up, x, y)

  /** A point [y] above the bar's centre, [front] in front of its plane (toward the user). */
  private fun at(x: Float = 0f, y: Float = 0f, front: Float = 0.01f) = Vector3(x, y, 0.5f - front)

  /** A ray from a hand 40 cm in front of the bar, aimed at dock-local ([x], [y]). */
  private fun aimAt(x: Float, y: Float, pressed: Boolean = false): Pointer {
    val origin = Vector3(0f, -0.05f, 0.1f)
    return Pointer(origin, Vector3(x, y, 0.5f) - origin, pressed)
  }

  /** Pinches the bar, pulls it up past the threshold and lets go, then waits out the guard. */
  private fun reveal(g: DockGesture) {
    frame(g, pinch = at())
    assertEquals(Event.None, frame(g, pinch = at(), pressed = true))
    assertEquals(Event.None, frame(g, pinch = at(y = pull), pressed = true))
    assertEquals(Event.Revealed, frame(g))
    now += DockGesture.GUARD_MS
  }

  @Test
  fun optionsSitInARowCentredOnTheBar() {
    assertEquals(-0.12f, options[0], 1e-6f)
    assertEquals(0f, options[2], 1e-6f)
    assertEquals(0.12f, options[4], 1e-6f)
    val many = DockLayout.optionXs(20)
    assertEquals(DockLayout.MAX_SPAN_M, many.last() - many.first() + DockLayout.spacing(20), 1e-5f)
  }

  @Test
  fun theBarBulgesAsAHandAboutToPinchItApproaches() {
    val g = DockGesture()
    frame(g, pinch = Vector3(0f, 0f, 0.3f))
    assertEquals(0f, g.hover, 1e-6f)
    frame(g, pinch = Vector3(0.01f, 0.005f, 0.47f))
    assertTrue(g.hover in 0.4f..0.6f)
    frame(g, pinch = Vector3(0.01f, 0.005f, 0.5f))
    assertEquals(1f, g.hover, 1e-6f)
  }

  @Test
  fun aTrackedPinchAtTheBarReachesItThroughViewSpace() {
    // The headset reports joints in OpenXR VIEW space (-Z forward); the scene is +Z forward.
    val head = Pose(Vector3(0f, 1.6f, 0f), Quaternion.lookRotation(Vector3(0f, 0f, 1f)))
    val placed = DockPlacement.pose(head)
    val local = head.inverse().let { it.q * placed.t + it.t }
    val pinch = viewToScene(head, floatArrayOf(local.x, local.y, -local.z))
    val g = DockGesture()
    g.update(placed, listOf(null, Hand(pinch = pinch)), emptyList(), options, 16)
    assertEquals(1f, g.hover, 1e-4f)
  }

  @Test
  fun touchingOrTappingTheBarDoesNotRaiseTheDock() {
    val g = DockGesture()
    // A fingertip pressing the bar and even sliding up it, without a pinch, is a brush.
    assertEquals(Event.None, frame(g, down(Surface.Bar, 0.03f, -0.02f), tip = at()))
    assertEquals(Event.None, frame(g, move(Surface.Bar, 0f, 0.05f), up(Surface.Bar, 0f, 0.05f), tip = at(y = 0.05f)))
    assertFalse(g.revealed)
    assertFalse(g.pulling)
  }

  @Test
  fun pinchingTheBarAndPullingUpRaisesTheDock() {
    val g = DockGesture()
    frame(g, pinch = at(x = 0.02f, y = -0.01f))
    assertEquals(Event.None, frame(g, pinch = at(x = 0.02f, y = -0.01f), pressed = true))
    assertTrue(g.pulling)
    assertEquals(1f, g.hover, 1e-6f)
    // The bar follows the pull until it has gone far enough.
    assertEquals(Event.None, frame(g, pinch = at(x = 0.02f, y = -0.01f + pull / 2f), pressed = true))
    assertEquals(0.5f, g.lift, 1e-3f)
    assertFalse(g.revealed)
    assertEquals(Event.None, frame(g, pinch = at(x = 0.025f, y = -0.01f + pull), pressed = true))
    assertTrue(g.pulling)
    assertEquals(1f, g.lift, 1e-6f)
    // The sheet reaches the top without abandoning the held pinch.
    assertEquals(Event.None, frame(g, pinch = at(x = 0.025f, y = -0.01f + pull), pressed = true))
    assertEquals(Event.Revealed, frame(g))
    assertTrue(g.revealed)
    assertEquals(1f, g.lift, 1e-6f)
  }

  @Test
  fun aPinchThatLetsGoBeforePullingFarEnoughDoesNothing() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    frame(g, pinch = at(y = pull * 0.3f), pressed = true)
    assertEquals(Event.None, frame(g, pinch = at(y = pull * 0.3f)))
    assertFalse(g.pulling)
    now += DockGesture.SETTLE_MS
    frame(g)
    assertEquals(0f, g.lift, 1e-6f)
    // Moving on up with the hand open does not finish the pull.
    assertEquals(Event.None, frame(g, pinch = at(y = 0.06f)))
    assertFalse(g.revealed)
  }

  @Test
  fun theSameHeldPinchCanReverseAtTheTopAndCloseTheSheet() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    for (fraction in listOf(0.2f, 0.6f, 1f, 0.7f, 0.3f, 0f)) {
      assertEquals(Event.None, frame(g, pinch = at(y = pull * fraction), pressed = true))
      assertEquals(fraction, g.lift, 1e-5f)
      assertTrue(g.pulling)
    }
    assertEquals(Event.None, frame(g))
    assertFalse(g.revealed)
    assertFalse(g.bodyVisible)
  }

  @Test
  fun releasingPastHalfwaySettlesToTheTopWithoutJumpingOnRelease() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    frame(g, pinch = at(y = pull * 0.65f), pressed = true)
    assertEquals(Event.Revealed, frame(g))
    assertEquals(0.65f, g.lift, 1e-5f)
    frame(g)
    assertTrue(g.lift > 0.65f && g.lift < 1f)
    now += DockGesture.SETTLE_MS
    frame(g)
    assertEquals(1f, g.lift, 1e-6f)
  }

  @Test
  fun aShortDownwardSwipeReturnsToTheTopAndCanBeRegrabbedImmediately() {
    val g = DockGesture()
    reveal(g)
    frame(g, pinch = at(y = pull), pressed = true)
    frame(g, pinch = at(y = pull * 0.8f), pressed = true)
    assertEquals(Event.None, frame(g))
    assertTrue(g.revealed)
    assertEquals(0.8f, g.lift, 1e-5f)
    // Interrupt the settle at the currently rendered handle, retaining its grab offset.
    frame(g)
    val before = g.lift
    frame(g, pinch = at(y = pull * before), pressed = true)
    assertTrue(g.pulling)
    val grabbed = g.lift
    frame(g, pinch = at(y = pull * before), pressed = true)
    assertEquals(grabbed, g.lift, 1e-5f)
    frame(g, pinch = at(y = 0f), pressed = true)
    assertEquals(Event.Dismissed, frame(g))
  }

  @Test
  fun releasingASidewaysSweepCannotOpenTheSheet() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    frame(g, pinch = at(x = 0.4f, y = pull), pressed = true)
    assertEquals(Event.None, frame(g))
    assertFalse(g.revealed)
    now += DockGesture.SETTLE_MS
    frame(g)
    assertEquals(0f, g.lift, 1e-6f)
  }

  @Test
  fun anExpandedRayHandleCanBePinchedAndSwipedBackDown() {
    val g = DockGesture()
    reveal(g)
    frame(g, pointer = aimAt(0f, pull))
    frame(g, pointer = aimAt(0f, pull), pressed = true)
    assertTrue(g.pulling)
    frame(g, pointer = aimAt(0f, pull * 0.4f), pressed = true)
    assertEquals(0.4f, g.lift, 1e-5f)
    assertEquals(Event.Dismissed, frame(g))
  }

  @Test
  fun theCollapsedLocationCannotGrabTheExpandedHandle() {
    val g = DockGesture()
    reveal(g)
    frame(g, pinch = at(), pointer = aimAt(0f, 0f), pressed = true)
    assertFalse(g.pulling)
    assertTrue(g.revealed)
  }

  @Test
  fun anotherHandCannotStealAHeldSwipe() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    frame(g, pinch = at(y = pull * 0.4f), pressed = true)
    g.update(bar, listOf(Hand(pinch = at(y = pull * 0.4f), pressed = true), Hand(pinch = at(y = pull * 0.6f), pressed = true)), emptyList(), options, now + 16)
    assertEquals(0.6f, g.lift, 1e-5f)
    assertTrue(g.pulling)
  }

  @Test
  fun pausedDockResetsAPartiallyRaisedSheet() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    frame(g, pinch = at(y = pull * 0.6f), pressed = true)
    g.reset()
    assertFalse(g.pulling)
    assertFalse(g.bodyVisible)
    assertEquals(Event.None, frame(g, pinch = at(y = pull), pressed = true))
  }

  @Test
  fun aPinchThatClosedAwayFromTheBarDoesNotGrabIt() {
    val g = DockGesture()
    // Closed 20 cm in front of the bar, then swept onto it and up.
    frame(g, pinch = at(front = 0.2f))
    frame(g, pinch = at(front = 0.2f), pressed = true)
    frame(g, pinch = at(), pressed = true)
    assertFalse(g.pulling)
    assertEquals(Event.None, frame(g, pinch = at(y = pull + 0.01f), pressed = true))
    // Closed above the bar, then pulled further up.
    frame(g, pinch = at(y = 0.1f))
    frame(g, pinch = at(y = 0.1f), pressed = true)
    assertEquals(Event.None, frame(g, pinch = at(y = 0.1f + pull + 0.01f), pressed = true))
    assertFalse(g.revealed)
  }

  @Test
  fun aSidewaysSweepDoesNotRaiseTheDock() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    assertEquals(Event.None, frame(g, pinch = at(x = 0.4f, y = pull), pressed = true))
    assertFalse(g.revealed)
    // Straightening up raises it.
    assertEquals(Event.None, frame(g, pinch = at(x = 0.02f, y = pull), pressed = true))
    assertEquals(Event.Revealed, frame(g))
  }

  @Test
  fun aPinchedRayOnTheBarPullsItUpTheSameWay() {
    // A controller's trigger, or a hand pinching along its ray from further away.
    val g = DockGesture()
    frame(g, pointer = aimAt(0f, 0f))
    assertTrue(g.hover > 0f)
    assertEquals(Event.None, frame(g, pointer = aimAt(0f, 0f, pressed = true), pressed = true))
    assertTrue(g.pulling)
    assertEquals(Event.None, frame(g, pointer = aimAt(0f, pull, pressed = true), pressed = true))
    assertEquals(1f, g.lift, 1e-6f)
    assertEquals(Event.Revealed, frame(g))
    assertTrue(g.revealed)
  }

  @Test
  fun aRayPinchThatClosedOffTheBarDoesNotGrabIt() {
    val g = DockGesture()
    frame(g, pointer = aimAt(0.2f, 0f))
    frame(g, pointer = aimAt(0.2f, 0f), pressed = true)
    frame(g, pointer = aimAt(0f, 0f), pressed = true)
    assertEquals(Event.None, frame(g, pointer = aimAt(0f, pull + 0.01f), pressed = true))
    assertFalse(g.revealed)
  }

  @Test
  fun touchesOnTheHiddenBodyDoNothing() {
    val g = DockGesture()
    assertEquals(Event.None, frame(g, down(Surface.Body, 0f, row)))
    assertFalse(g.revealed)
  }

  @Test
  fun theHandThatRaisedTheDockRunsNothingUntilItLetsGo() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    assertEquals(Event.None, frame(g, pinch = at(y = pull), pressed = true))
    // The pulling hand ends among the options: touching them while held runs nothing, however long.
    now += DockGesture.GUARD_MS * 3
    assertEquals(Event.None, frame(g, down(Surface.Body, 0f, row), pinch = at(y = pull), pressed = true))
    assertEquals(Event.None, frame(g, down(Surface.Bar, 0f, 0f), pinch = at(y = pull), pressed = true))
    assertTrue(g.pulling)
    // Letting go starts the guard, which a finger landing straight after must also respect.
    assertEquals(Event.Revealed, frame(g, tip = at(y = row)))
    assertEquals(Event.None, frame(g, down(Surface.Body, 0f, row), tip = at(y = row)))
    now += DockGesture.GUARD_MS
    assertEquals(Event.Activated(2), frame(g, down(Surface.Body, 0f, row), tip = at(y = row)))
  }

  @Test
  fun touchingAnOptionRunsItAtOnceAndLowersTheDock() {
    val g = DockGesture()
    reveal(g)
    // A plain touch: no slide, flick or release needed.
    assertEquals(Event.Activated(3), frame(g, down(Surface.Body, 0.055f, row + 0.01f)))
    assertFalse(g.revealed)
  }

  @Test
  fun aPinchedRayOnAnOptionRunsItTheSameWay() {
    val g = DockGesture()
    reveal(g)
    val origin = Vector3(-0.06f, 0.3f, -0.2f)
    val pointer = Pointer(origin, Vector3(-0.06f, row, 0.5f) - origin)
    frame(g, pointer = pointer)
    assertEquals(1, g.highlighted)
    assertEquals(Event.Activated(1), frame(g, down(Surface.Body, -0.06f, row), pointer = pointer))
  }

  @Test
  fun aFingertipNearAnOptionHighlightsItSoItsLabelShows() {
    val g = DockGesture()
    reveal(g)
    frame(g, tip = Vector3(0.12f, row, 0.3f)) // too far in front
    assertNull(g.highlighted)
    frame(g, tip = Vector3(0.12f, row + 0.01f, 0.47f))
    assertEquals(4, g.highlighted)
    frame(g, tip = Vector3(0.09f, row, 0.48f)) // drifting toward the next one keeps it until clearly nearer
    assertEquals(4, g.highlighted)
    frame(g, tip = Vector3(0.06f, row, 0.48f))
    assertEquals(3, g.highlighted)
    // The touch runs the highlighted option when it lands between two.
    assertEquals(Event.Activated(3), frame(g, down(Surface.Body, 0.085f, row), tip = Vector3(0.085f, row, 0.5f)))
  }

  @Test
  fun touchingBesideTheRowRunsNothing() {
    val g = DockGesture()
    reveal(g)
    assertEquals(Event.None, frame(g, down(Surface.Body, 0.2f, DockPlacement.LABEL_Y_M))) // the label space
    assertEquals(Event.None, frame(g, down(Surface.Body, 0.2f, DockPlacement.SHEET_BOTTOM_M + 0.003f))) // the sheet's bottom margin
    assertEquals(Event.None, frame(g, down(Surface.Body, 0.25f, row))) // past the last option
    assertTrue(g.revealed)
  }

  @Test
  fun theExpandedHandleStaysAtTheTopAndRequiresASwipeDown() {
    val g = DockGesture()
    reveal(g)
    frame(g, pinch = at(y = pull, front = 0.03f))
    assertTrue(g.hover > 0.4f)
    assertEquals(Event.None, frame(g, down(Surface.Bar, 0.02f, 0.01f)))
    assertTrue(g.revealed)
    frame(g, pinch = at(y = pull), pressed = true)
    assertTrue(g.pulling)
    frame(g, pinch = at(y = pull * 0.6f), pressed = true)
    assertEquals(0.6f, g.lift, 1e-5f)
    frame(g, pinch = at(), pressed = true)
    assertEquals(0f, g.lift, 1e-5f)
    assertEquals(Event.Dismissed, frame(g))
    assertFalse(g.revealed)
    // The bar can be grabbed immediately, without waiting out the option touch guard.
    reveal(g)
    assertTrue(g.revealed)
  }

  @Test
  fun aFingerRestingOnTheBarDoesNotToggleIt() {
    val g = DockGesture()
    reveal(g)
    repeat(5) { assertEquals(Event.None, frame(g, down(Surface.Bar, 0f, 0f), pinch = at(y = pull))) }
    assertTrue(g.revealed)
  }

  @Test
  fun theExpandedSheetStaysAtTheTopWhenLeftAlone() {
    val g = DockGesture()
    reveal(g)
    now += 60_000
    assertEquals(Event.None, frame(g))
    assertTrue(g.revealed)
    assertEquals(1f, g.lift, 1e-6f)
  }

  @Test
  fun aNearRayFromATypingHandDoesNotBulgeTheBar() {
    val g = DockGesture()
    val origin = Vector3(0f, -0.05f, 0.35f) // 15 cm away, just below
    frame(g, pointer = Pointer(origin, Vector3(0f, 0f, 0.5f) - origin))
    assertEquals(0f, g.hover, 1e-6f)
  }

  @Test
  fun aPinchHeldWhileTheDockWasHiddenMustCloseAgain() {
    val g = DockGesture()
    g.reset()
    frame(g, pinch = at(), pressed = true)
    assertEquals(Event.None, frame(g, pinch = at(y = pull + 0.01f), pressed = true))
    assertFalse(g.revealed)
  }

  @Test
  fun theHandleIsTheSheetsTopEdgeDuringTheWholeSwipe() {
    for (lift in listOf(0f, 0.25f, 0.5f, 1f)) {
      val handle = DockPlacement.handlePose(bar, lift)
      assertEquals(pull * lift, handle.t.y, 1e-6f)
      // The handle rides inside the sheet's top edge, never apart from it.
      val sheet = DockPlacement.sheetPose(bar, lift)
      val sheetTop = sheet.t.y + DockPlacement.sheetHeight(lift) / 2f
      assertEquals(DockPlacement.GRABBER_INSET_M, sheetTop - handle.t.y, 1e-6f)
      // The sheet grows up out of the bar's resting place: its bottom edge stays put.
      assertEquals(DockPlacement.SHEET_BOTTOM_M, sheet.t.y - DockPlacement.sheetHeight(lift) / 2f, 1e-6f)
      // The handle's panel is in front of the sheet, so rays meet the handle first.
      assertTrue(handle.t.z < DockPlacement.sheetPanelPose(bar).t.z)
    }
    // The sheet's panel does not move; the drawn sheet grows inside it.
    assertEquals(DockPlacement.SHEET_TOP_M, DockPlacement.sheetPanelPose(bar).t.y + DockPlacement.SHEET_HEIGHT_M / 2f, 1e-6f)
    assertEquals(0f, DockPlacement.sheetDrop(1f), 1e-6f)
    // Expanded, the handle's target clears the label, which clears even a magnified icon.
    assertTrue(pull - DockPlacement.BAR_HIT_HEIGHT_M / 2f > DockPlacement.LABEL_Y_M + DockPlacement.LABEL_HALF_HEIGHT_M)
    assertTrue(DockPlacement.MAGNIFIED_TOP_M < DockPlacement.LABEL_Y_M - DockPlacement.LABEL_HALF_HEIGHT_M)
    // A full-size tile is inside the row's touch band, and its running dot (in the margin below it) inside the sheet.
    val tileBottom = DockPlacement.ROW_Y_M - DockPlacement.TILE_M / 2f
    val dotBottom = tileBottom - DockLayout.MAX_SPACING_M * (DockShelf.PAD + DockShelf.DOT) / 2f
    assertTrue(dotBottom > DockPlacement.SHEET_BOTTOM_M)
    assertTrue(tileBottom > row - 0.03f)
    assertTrue(row + DockPlacement.TILE_M / 2f < row + 0.03f)
  }

  @Test
  fun aSeparatorSplitsTheTerminalsFromTheToolsLikeTheMacDock() {
    // Connection, two windows | Keyboard, Arrange, Settings.
    val xs = DockLayout.optionXs(6, separatorBefore = 3)
    val spacing = DockLayout.spacingOf(xs)
    assertEquals(DockLayout.MAX_SPACING_M, spacing, 1e-6f)
    assertEquals(spacing, xs[1] - xs[0], 1e-6f)
    assertEquals(spacing * (1f + DockShelf.SEPARATOR), xs[3] - xs[2], 1e-6f)
    assertEquals(0f, xs.first() + xs.last(), 1e-6f) // still centred on the bar
    assertEquals((xs[2] + xs[3]) / 2f, DockLayout.separatorX(xs, 3)!!, 1e-6f)
    assertNull(DockLayout.separatorX(xs, 0))
    assertNull(DockLayout.separatorX(xs, null))
    // A full row shrinks its icons, separator included, to stay within the span.
    val many = DockLayout.optionXs(20, separatorBefore = 17)
    val small = DockLayout.spacingOf(many)
    assertEquals(DockLayout.spacing(20, separated = true), small, 1e-6f)
    assertEquals(DockLayout.MAX_SPAN_M, many.last() - many.first() + small, 1e-5f)
  }

  @Test
  fun aFullRowAcceptsTouchesOnlyOverItsShrunkenIcons() {
    val many = DockLayout.optionXs(20, separatorBefore = 17)
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    frame(g, pinch = at(y = pull), pressed = true)
    g.update(bar, listOf(null, null), emptyList(), many, now + 16)
    now += 16 + DockGesture.GUARD_MS
    val spacing = DockLayout.spacingOf(many)
    val last = many.last()
    assertEquals(Event.None, g.update(bar, listOf(null, null), listOf(down(Surface.Body, last + spacing * 0.6f, row)), many, now))
    assertEquals(Event.Activated(19), g.update(bar, listOf(null, null), listOf(down(Surface.Body, last + spacing * 0.4f, row)), many, now + 16))
  }

  @Test
  fun theFingertipsPositionDrivesTheMagnificationWhileItHighlightsAnOption() {
    val g = DockGesture()
    reveal(g)
    assertNull(g.pointedX)
    frame(g, tip = Vector3(0.071f, row, 0.48f))
    assertEquals(3, g.highlighted)
    assertEquals(0.071f, g.pointedX!!, 1e-5f)
    frame(g, tip = Vector3(0.071f, row, 0.3f)) // pulled back out of reach
    assertNull(g.highlighted)
    assertNull(g.pointedX)
    frame(g, tip = Vector3(0.05f, row, 0.48f))
    assertEquals(Event.Activated(3), frame(g, down(Surface.Body, 0.05f, row), tip = Vector3(0.05f, row, 0.5f)))
    assertNull(g.pointedX)
  }

  @Test
  fun theSheetFadesInAtTheStartOfTheSwipe() {
    assertEquals(0f, DockPlacement.sheetOpacity(0f), 1e-6f)
    assertTrue(DockPlacement.sheetOpacity(0.1f) in 0.2f..0.8f)
    assertEquals(1f, DockPlacement.sheetOpacity(0.3f), 1e-6f)
  }

  @Test
  fun aPinchClosingOnTheTopOfTheRowDoesNotGrabTheExpandedHandle() {
    val g = DockGesture()
    reveal(g)
    val top = row + 0.03f
    frame(g, pinch = at(y = top))
    frame(g, pinch = at(y = top), pressed = true)
    assertFalse(g.pulling)
    frame(g)
    // The handle just above it can still be pinched.
    frame(g, pinch = at(y = pull - 0.04f))
    frame(g, pinch = at(y = pull - 0.04f), pressed = true)
    assertTrue(g.pulling)
  }

  @Test
  fun panelTouchesMapToDockLocalMetres() {
    val (cx, cy) = DockPlacement.touchPoint(Surface.Bar, 0.5f, 0.5f)
    assertEquals(0f, cx, 1e-6f)
    assertEquals(0f, cy, 1e-6f)
    val (_, top) = DockPlacement.touchPoint(Surface.Bar, 0.5f, 0f)
    assertEquals(DockPlacement.BAR_HIT_HEIGHT_M / 2f, top, 1e-6f)
    val (left, panelTop) = DockPlacement.touchPoint(Surface.Body, 0f, 0f)
    assertEquals(-DockPlacement.BODY_WIDTH_M / 2f, left, 1e-6f)
    assertEquals(DockPlacement.SHEET_TOP_M, panelTop, 1e-6f)
    val (_, bottom) = DockPlacement.touchPoint(Surface.Body, 0.5f, 1f)
    assertEquals(DockPlacement.SHEET_BOTTOM_M, bottom, 1e-6f)
  }

  @Test
  fun recallPlacesTheWindowAboveTheDockAndGlides() {
    val eye = Vector3(0f, 0f, 0f)
    val lowBar = Pose(Vector3(0f, -0.3f, 0.35f), Quaternion.lookRotation(Vector3(0f, -0.3f, 0.35f)))
    val target = DockRecall.targetPose(eye, lowBar, heightM = 0.5f, distanceM = 1.1f)
    assertTrue(target.t.z > 0.8f)
    // As seen from the eye, the window's bottom edge sits well above the bar.
    fun elevationDeg(y: Float, z: Float) = Math.toDegrees(kotlin.math.atan2(y, z).toDouble())
    val bottom = elevationDeg(target.t.y - 0.25f, target.t.z)
    assertEquals(elevationDeg(lowBar.t.y, lowBar.t.z) + DockRecall.CLEARANCE_DEG, bottom, 1.0)
    val recall = DockRecall(durationMs = 100)
    val start = Pose(Vector3(2f, 0f, 0f), Quaternion.lookRotation(Vector3(2f, 0f, 0f)))
    recall.start(7, start, target, nowMs = 0)
    val mid = recall.tick(50)!!
    assertEquals(7, mid.first)
    assertTrue(mid.second.t.x in 0.1f..1.9f)
    val end = recall.tick(120)!!
    assertEquals(target.t.x, end.second.t.x, 1e-5f)
    assertFalse(recall.active)
  }
}
