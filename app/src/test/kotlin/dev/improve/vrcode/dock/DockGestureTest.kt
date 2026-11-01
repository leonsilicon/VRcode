package dev.improve.vrcode.dock

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
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
  private val pull = DockGesture.REVEAL_DRAG_M
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
    assertEquals(Event.Revealed, frame(g, pinch = at(y = pull + 0.005f), pressed = true))
    frame(g)
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
    assertEquals(Event.Revealed, frame(g, pinch = at(x = 0.025f, y = -0.01f + pull + 0.002f), pressed = true))
    assertTrue(g.revealed)
    assertFalse(g.pulling)
    assertEquals(0f, g.lift, 1e-6f)
    // Still holding: no further event, and the dock stays up.
    assertEquals(Event.None, frame(g, pinch = at(y = 0.06f), pressed = true))
    assertTrue(g.revealed)
  }

  @Test
  fun aPinchThatLetsGoBeforePullingFarEnoughDoesNothing() {
    val g = DockGesture()
    frame(g, pinch = at())
    frame(g, pinch = at(), pressed = true)
    frame(g, pinch = at(y = pull * 0.8f), pressed = true)
    assertEquals(Event.None, frame(g, pinch = at(y = pull * 0.8f)))
    assertFalse(g.pulling)
    assertEquals(0f, g.lift, 1e-6f)
    // Moving on up with the hand open does not finish the pull.
    assertEquals(Event.None, frame(g, pinch = at(y = 0.06f)))
    assertFalse(g.revealed)
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
    assertEquals(Event.None, frame(g, pinch = at(x = 0.06f, y = pull + 0.005f), pressed = true))
    assertFalse(g.revealed)
    // Straightening up raises it.
    assertEquals(Event.Revealed, frame(g, pinch = at(x = 0.02f, y = pull + 0.005f), pressed = true))
  }

  @Test
  fun aPinchedRayOnTheBarPullsItUpTheSameWay() {
    // A controller's trigger, or a hand pinching along its ray from further away.
    val g = DockGesture()
    frame(g, pointer = aimAt(0f, 0f))
    assertTrue(g.hover > 0f)
    assertEquals(Event.None, frame(g, pointer = aimAt(0f, 0f, pressed = true), pressed = true))
    assertTrue(g.pulling)
    assertEquals(Event.Revealed, frame(g, pointer = aimAt(0f, pull + 0.005f, pressed = true), pressed = true))
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
    assertEquals(Event.Revealed, frame(g, pinch = at(y = pull + 0.005f), pressed = true))
    // The pulling hand ends among the options: touching them while held runs nothing, however long.
    now += DockGesture.GUARD_MS * 3
    assertEquals(Event.None, frame(g, down(Surface.Body, 0f, row), pinch = at(y = row), pressed = true))
    assertEquals(Event.None, frame(g, down(Surface.Bar, 0f, 0f), pinch = at(y = row), pressed = true))
    assertTrue(g.revealed)
    // Letting go starts the guard, which a finger landing straight after must also respect.
    frame(g, tip = at(y = row))
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
    assertEquals(Event.None, frame(g, down(Surface.Body, 0.2f, DockPlacement.BODY_TOP_M + 0.03f))) // the label space
    assertEquals(Event.None, frame(g, down(Surface.Body, 0.25f, row))) // past the last option
    assertTrue(g.revealed)
  }

  @Test
  fun touchingTheBarAgainLowersTheDock() {
    val g = DockGesture()
    reveal(g)
    // The bar stays where it is under the raised options, and still bulges under a hand.
    frame(g, pinch = Vector3(0f, 0f, 0.47f))
    assertTrue(g.hover > 0.4f)
    assertEquals(Event.Dismissed, frame(g, down(Surface.Bar, 0.02f, 0.01f)))
    assertFalse(g.revealed)
    now += DockGesture.GUARD_MS
    // Down again, touching it does nothing; a pinch pulls it back up.
    assertEquals(Event.None, frame(g, down(Surface.Bar, 0f, 0f)))
    reveal(g)
    assertTrue(g.revealed)
  }

  @Test
  fun aFingerRestingOnTheBarAfterLoweringItDoesNotPullItStraightBack() {
    val g = DockGesture()
    reveal(g)
    assertEquals(Event.Dismissed, frame(g, down(Surface.Bar, 0f, 0f), pinch = at()))
    // Direct touch can report a resting finger as a quick release and press again.
    assertEquals(Event.None, frame(g, down(Surface.Bar, 0f, 0f), pinch = at()))
    // A pinch closing straight after the dock lowered does not grab the bar, even pulled up later.
    assertEquals(Event.None, frame(g, pinch = at(), pressed = true))
    now += DockGesture.GUARD_MS
    assertEquals(Event.None, frame(g, pinch = at(y = pull + 0.01f), pressed = true))
    assertFalse(g.revealed)
  }

  @Test
  fun theRaisedDockLowersItselfWhenLeftAlone() {
    val g = DockGesture()
    reveal(g)
    now += DockGesture.IDLE_MS + 100
    assertEquals(Event.Dismissed, frame(g))
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
  fun theRaisedBodySitsAboveTheBarWithoutOverlappingIt() {
    assertTrue(DockPlacement.BODY_BOTTOM_M > DockPlacement.BAR_HIT_HEIGHT_M / 2f)
    assertTrue(DockPlacement.ROW_Y_M - 0.03f > DockPlacement.BAR_HIT_HEIGHT_M / 2f)
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
    assertEquals(DockPlacement.BODY_PANEL_TOP_M, panelTop, 1e-6f)
    val (_, bottom) = DockPlacement.touchPoint(Surface.Body, 0.5f, 1f)
    assertEquals(DockPlacement.BODY_BOTTOM_M, bottom, 1e-6f)
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
