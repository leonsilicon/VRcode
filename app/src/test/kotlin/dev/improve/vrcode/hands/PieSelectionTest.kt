package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PieSelectionTest {
  private fun assertVector(expected: Vector3, actual: Vector3, delta: Float = 1e-4f) {
    assertEquals(expected.x, actual.x, delta)
    assertEquals(expected.y, actual.y, delta)
    assertEquals(expected.z, actual.z, delta)
  }

  @Test
  fun radialMenuPicksClockwiseFromTheTopAndReachesAtTheRing() {
    val menu = RadialMenu(6, deadZoneM = 0.04f, reachM = 0.07f)
    assertNull(menu.pick(0.01f, 0.02f))
    assertEquals(0, menu.pick(0f, 0.06f))
    assertEquals(1, menu.pick(0.05f, 0.03f))
    assertEquals(3, menu.pick(0f, -0.06f))
    assertEquals(4, menu.pick(-0.05f, -0.03f))
    assertEquals(5, menu.pick(-0.05f, 0.03f))
    assertTrue(!menu.reached(0f, 0.06f) && menu.reached(0f, 0.071f))
  }

  @Test
  fun alongMeasuresHowFarTheHandGoesInAnItemsDirection() {
    val menu = RadialMenu(4)
    assertEquals(0.1f, menu.along(0, 0.03f, 0.1f), 1e-6f) // top: up
    assertEquals(0.03f, menu.along(1, 0.03f, 0.1f), 1e-6f) // right
    assertEquals(-0.1f, menu.along(2, 0.03f, 0.1f), 1e-6f) // bottom
    assertEquals(-0.03f, menu.along(3, 0.03f, 0.1f), 1e-6f) // left
    assertEquals(0.05f * kotlin.math.sqrt(2f), RadialMenu(8).along(1, 0.05f, 0.05f), 1e-6f)
  }

  @Test
  fun aViewSpacePointAheadLandsInFrontOfTheHeadNotBehindIt() {
    // OpenXR VIEW space looks down -Z; Spatial's scene looks down +Z.
    for (yaw in listOf(0f, 90f, -135f)) {
      val head = Pose(Vector3(0.2f, 1.6f, -0.5f), Quaternion(10f, yaw, 0f))
      val ahead = viewToScene(head, floatArrayOf(0f, 0f, -0.4f))
      assertVector(head.t + head.forward() * 0.4f, ahead)
      // VIEW +X is the head's right, +Y its up.
      assertVector(head.t + head.q * Vector3(0.1f, 0.2f, 0f), viewToScene(head, floatArrayOf(0.1f, 0.2f, 0f)))
    }
  }

  @Test
  fun movingTowardTheUsersRightPicksTheRightItem() {
    val head = Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 0f, 0f))
    val s = PieSelection(RadialMenu(4, deadZoneM = 0.035f, reachM = 0.07f))
    val center = viewToScene(head, floatArrayOf(0f, -0.2f, -0.4f))
    s.open(center, head)
    assertVector(Vector3(1f, 0f, 0f), s.right) // Spatial's +X is the user's right
    s.update(center + Vector3(0.01f, 0f, 0f))
    assertNull(s.highlighted)
    // The hand moves right in VIEW space (+X); the scene sees the same direction.
    s.update(viewToScene(head, floatArrayOf(0.05f, -0.2f, -0.4f)))
    assertEquals(1, s.highlighted)
    assertNull(s.reached)
    s.update(viewToScene(head, floatArrayOf(0.08f, -0.2f, -0.4f)))
    assertEquals(1, s.reached)
    // A swipe on through the right item is measured along it, not along the others.
    assertEquals(0.08f, s.along(1), 1e-4f)
    assertEquals(0f, s.along(0), 1e-4f)
    assertEquals(-0.08f, s.along(3), 1e-4f)
    s.update(viewToScene(head, floatArrayOf(-0.05f, -0.2f, -0.4f)))
    assertEquals(3, s.highlighted)
    s.update(viewToScene(head, floatArrayOf(0f, -0.15f, -0.4f)))
    assertEquals(0, s.highlighted)
  }

  @Test
  fun theMenusRightAxisIsTheTurnedHeadsRight() {
    for (yaw in listOf(30f, 90f, 200f)) {
      val head = Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, yaw, 0f))
      val s = PieSelection(RadialMenu(4))
      s.open(viewToScene(head, floatArrayOf(0f, -0.2f, -0.4f)), head)
      assertVector(head.q * Vector3(1f, 0f, 0f), s.right)
      assertVector(head.forward(), s.forward)
    }
  }

  @Test
  fun walkingWhilePinchedCarriesTheMenuAlong() {
    // Opened with the hand down to the left, so walking straight ahead crosses the menu's right axis.
    val start = Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 0f, 0f))
    val hand = floatArrayOf(-0.25f, -0.2f, -0.35f) // VIEW space, held still relative to the head
    val s = PieSelection(RadialMenu(4, deadZoneM = 0.035f, reachM = 0.07f))
    s.open(viewToScene(start, hand), start)
    for (t in listOf(Vector3(0f, 1.63f, 0.4f), Vector3(0.1f, 1.58f, 1.2f), Vector3(-0.5f, 1.6f, 2.5f))) {
      val walked = Pose(t, start.q)
      s.follow(walked)
      s.update(viewToScene(walked, hand))
      assertNull(s.highlighted)
      assertEquals(0f, s.distanceM, 1e-4f)
    }
    // After the walk, the hand's own movement still picks: right of the pinch is the right item.
    val walked = Pose(Vector3(-0.5f, 1.6f, 2.5f), start.q)
    s.update(viewToScene(walked, floatArrayOf(-0.25f, -0.2f, -0.35f)) + s.right * 0.08f)
    assertEquals(1, s.reached)
  }

  @Test
  fun withoutFollowingTheSameWalkWouldPickAnItem() {
    // What the menus did before they followed the body: the walk alone reached an item.
    val start = Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 0f, 0f))
    val hand = floatArrayOf(-0.25f, -0.2f, -0.35f)
    val s = PieSelection(RadialMenu(4, deadZoneM = 0.035f, reachM = 0.07f))
    s.open(viewToScene(start, hand), start)
    s.update(viewToScene(Pose(Vector3(0f, 1.6f, 0.4f), start.q), hand))
    assertTrue(s.reached != null)
  }

  @Test
  fun turningOrNoddingTheHeadAboutTheNeckLeavesTheMenuWhereItWas() {
    val start = Pose(Vector3(0.3f, 1.6f, -0.2f), Quaternion(0f, 40f, 0f))
    val neck = neckPoint(start)
    val s = PieSelection(RadialMenu(4, deadZoneM = 0.035f, reachM = 0.07f))
    val opened = viewToScene(start, floatArrayOf(0f, -0.2f, -0.4f))
    s.open(opened, start)
    for (q in listOf(Quaternion(25f, 40f, 0f), Quaternion(-10f, 70f, 0f), Quaternion(30f, 15f, 8f))) {
      // A real head turns about the neck, so the tracked eyes move as well as rotate.
      val turned = Pose(neck + q * (EYES_TO_NECK * -1f), q)
      assertVector(neck, neckPoint(turned))
      s.follow(turned)
      assertVector(opened, s.center!!)
      s.update(opened) // the hand held still
      assertNull(s.highlighted)
    }
  }

  @Test
  fun aClosedMenuDoesNotFollow() {
    val head = Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 0f, 0f))
    val s = PieSelection(RadialMenu(3))
    s.open(viewToScene(head, floatArrayOf(0f, 0f, -0.4f)), head)
    s.close()
    s.follow(Pose(Vector3(1f, 1.6f, 1f), head.q))
    assertNull(s.center)
  }

  @Test
  fun closingForgetsTheSelection() {
    val head = Pose(Vector3(0f, 1.6f, 0f), Quaternion(0f, 0f, 0f))
    val s = PieSelection(RadialMenu(3))
    val c = viewToScene(head, floatArrayOf(0f, 0f, -0.4f))
    s.open(c, head)
    s.update(c + Vector3(0f, 0.08f, 0f))
    assertEquals(0, s.reached)
    s.close()
    assertNull(s.center)
    assertNull(s.highlighted)
    assertNull(s.reached)
  }
}
