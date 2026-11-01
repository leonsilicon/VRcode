package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.QuickInput
import dev.improve.vrcode.voice.ThinkingPlacement
import dev.improve.xmux.client.Key
import dev.improve.xmux.client.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBarTest {
  @Test fun theBarHoldsTheKeysTerminalsAndAgentsAskForOnce() {
    assertEquals(
      listOf(QuickInput.Escape, QuickInput.Tab, QuickInput.ShiftTab, QuickInput.Left, QuickInput.Up, QuickInput.Down,
        QuickInput.Right, QuickInput.Backspace, QuickInput.CtrlC, QuickInput.Enter, QuickInput.Keyboard),
      KeyBarKeys.keys,
    )
    assertEquals(KeyBarKeys.keys.size, KeyBarKeys.keys.toSet().size)
  }

  @Test fun arrowsAndBackspaceRepeatWhileHeldButEnterAndCtrlCSendOnce() {
    for (input in listOf(QuickInput.Left, QuickInput.Up, QuickInput.Down, QuickInput.Right, QuickInput.Backspace)) {
      assertTrue("$input repeats", KeyBarKeys.repeats(input))
    }
    for (input in listOf(QuickInput.Escape, QuickInput.Tab, QuickInput.ShiftTab, QuickInput.CtrlC, QuickInput.Enter, QuickInput.Keyboard)) {
      assertFalse("$input sends once", KeyBarKeys.repeats(input))
    }
  }

  @Test fun eachKeySendsItsCmuxKeyWithCtrlForChords() {
    assertEquals(Key("enter"), QuickInput.Enter.xmuxKey())
    assertEquals(Key("escape"), QuickInput.Escape.xmuxKey())
    assertEquals(Key("backtab"), QuickInput.ShiftTab.xmuxKey())
    assertEquals(Key("up"), QuickInput.Up.xmuxKey())
    assertEquals(Key("c", setOf(Modifier.Ctrl)), QuickInput.CtrlC.xmuxKey())
    assertNull(QuickInput.Keyboard.xmuxKey())
  }

  @Test fun theBarSitsCentredUnderItsWindowPastTheBottomResizeBand() {
    val window = Pose(Vector3(0.3f, 1.4f, 1.2f), Quaternion(0f, 0f, 0f))
    val windowHeightM = 0.6f
    val barHeightM = 0.05f
    for (distance in listOf(0.5f, 1.2f, 3f)) {
      val gap = KeyBarPlacement.gapM(distance)
      assertTrue("clear of the band at $distance m", gap > EdgeBand.bandM(distance))
      val pose = KeyBarPlacement.pose(window, windowHeightM, barHeightM, gap)
      assertEquals(window.t.x, pose.t.x, 1e-4f)
      // Its top edge is the gap below the window's bottom edge.
      assertEquals(window.t.y - windowHeightM / 2f - gap, pose.t.y + barHeightM / 2f, 1e-4f)
      assertEquals(window.t.z - ThinkingPlacement.FORWARD_M, pose.t.z, 1e-4f)
    }
  }

  @Test fun aTiltedWindowKeepsItsBarSquareUnderIt() {
    val window = Pose(Vector3(0f, 1.3f, 1f), Quaternion(-20f, 35f, 0f))
    val pose = KeyBarPlacement.pose(window, 0.6f, 0.05f, KeyBarPlacement.gapM(1f))
    assertEquals(1f, pose.forward().normalize().dot(window.forward().normalize()), 1e-4f)
    val down = (pose.t - window.t).normalize()
    assertEquals(-1f, down.dot(window.q.times(Vector3(0f, 1f, 0f))), 1e-2f)
  }

  @Test fun aThinkingPanelBelowTheWindowStacksUnderTheBar() {
    val window = Pose(Vector3(0f, 1.4f, 1f), Quaternion(0f, 0f, 0f))
    val windowHeightM = 0.6f
    val barHeightM = 0.05f
    val panelHeightM = 0.2f
    val gap = KeyBarPlacement.gapM(1f)
    val bar = KeyBarPlacement.pose(window, windowHeightM, barHeightM, gap)
    val thinking = ThinkingPlacement.pose(window, windowHeightM, panelHeightM, above = false,
      offsetM = KeyBarPlacement.occupiedM(barHeightM, gap))
    val barBottom = bar.t.y - barHeightM / 2f
    val thinkingTop = thinking.t.y + panelHeightM / 2f
    assertEquals(barBottom - ThinkingPlacement.GAP_M, thinkingTop, 1e-4f)
  }
}
