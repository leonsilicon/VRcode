package dev.improve.vrcode.voice

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingPanelTest {
  private val windowHeightM = 0.6f
  private val panelHeightM = 0.2f
  private val window = Pose(Vector3(0.2f, 1.4f, 1.2f), Quaternion(0f, 0f, 0f))

  @Test fun sitsJustAboveTheWindowInItsPlane() {
    val pose = ThinkingPlacement.pose(window, windowHeightM, panelHeightM, above = true)
    assertEquals(window.t.x, pose.t.x, 1e-4f)
    // Its bottom edge is the gap above the window's top edge.
    assertEquals(window.t.y + windowHeightM / 2f + ThinkingPlacement.GAP_M, pose.t.y - panelHeightM / 2f, 1e-4f)
    assertEquals(window.t.z - ThinkingPlacement.FORWARD_M, pose.t.z, 1e-4f)
  }

  @Test fun belowMirrorsAbove() {
    val pose = ThinkingPlacement.pose(window, windowHeightM, panelHeightM, above = false)
    assertEquals(window.t.y - windowHeightM / 2f - ThinkingPlacement.GAP_M, pose.t.y + panelHeightM / 2f, 1e-4f)
  }

  @Test fun thinkingStacksAboveAnExistingTerminalInfoBox() {
    val occupied = panelHeightM + ThinkingPlacement.GAP_M
    val pose = ThinkingPlacement.pose(window, windowHeightM, panelHeightM, above = true, offsetM = occupied)
    val first = ThinkingPlacement.pose(window, windowHeightM, panelHeightM, above = true)
    assertEquals(first.t.y + panelHeightM / 2f + ThinkingPlacement.GAP_M, pose.t.y - panelHeightM / 2f, 1e-4f)
    assertEquals(first.t.z, pose.t.z, 1e-4f)
  }

  @Test fun followsATiltedWindowAlongItsOwnUpAxis() {
    val tilted = Pose(window.t, Quaternion(-30f, 45f, 0f))
    val pose = ThinkingPlacement.pose(tilted, windowHeightM, panelHeightM, above = true)
    val local = tilted.q.inverse().times(pose.t - tilted.t)
    assertEquals(0f, local.x, 1e-4f)
    assertEquals(windowHeightM / 2f + ThinkingPlacement.GAP_M + panelHeightM / 2f, local.y, 1e-4f)
    assertEquals(-ThinkingPlacement.FORWARD_M, local.z, 1e-4f)
    assertEquals(tilted.q, pose.q)
  }

  @Test fun traceJoinsThinkingChunksAndKeepsStepsInOrder() {
    val note = VoiceNote(1, VoiceNote.Mode.Ai)
    note.addProgress("status", "Claude Code in ~/vrcode")
    note.addProgress("thinking", "They want the tests. ")
    note.addProgress("thinking", "Checking package.json.")
    note.addProgress("step", "Read package.json")
    note.addProgress("thinking", "  It uses Bun.")
    note.addProgress("unknown", "ignored")
    assertEquals("Claude Code in ~/vrcode", note.aiStatus)
    assertEquals(
      listOf(AiTrace.Thought("They want the tests. Checking package.json."), AiTrace.Step("Read package.json"), AiTrace.Thought("It uses Bun.")),
      note.trace.toList(),
    )
  }

  @Test fun traceKeepsOnlyTheNewestEntries() {
    val note = VoiceNote(1, VoiceNote.Mode.Ai)
    repeat(60) { note.addProgress("step", "$ step $it") }
    assertEquals(40, note.trace.size)
    assertEquals(AiTrace.Step("$ step 59"), note.trace.last())
    note.clearAgent()
    assertEquals(0, note.trace.size)
  }
}
