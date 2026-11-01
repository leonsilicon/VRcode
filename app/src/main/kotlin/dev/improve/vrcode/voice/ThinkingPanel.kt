package dev.improve.vrcode.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.voice.VoiceNote.Phase
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial

private val Face = Color(0xEB15161B)
private val Rim = Color(0x40FFFFFF)
private val Muted = Color(0xFF8A8F98)
private val Thought = Color(0xFFB9BDC6)
private val StepText = Color(0xFFDCE3F0)
private val Violet = Color(0xFF8A5CF6)
private val Done = Color(0xFF22A45D)
private val Problem = Color(0xFFFF6B6B)
private val StopFace = Color(0xFF3A3D45)

/** Where a thinking panel sits: centred just above or below its window, in its plane, facing the same way. */
internal object ThinkingPlacement {
  /** Space between the window's edge and the panel. */
  const val GAP_M = 0.015f

  /** The panel stands this far in front of the window's plane, so the two never z-fight. */
  const val FORWARD_M = 0.005f

  /**
   * The centre pose of a panel [panelHeightM] tall, given its window's centre pose [window] and
   * height [windowHeightM] (header included): along the window's own up axis, so a tilted window
   * keeps its panel square to it. A panel's visible face is its local -Z, so forward is -Z.
   */
  fun pose(window: Pose, windowHeightM: Float, panelHeightM: Float, above: Boolean, offsetM: Float = 0f): Pose {
    val up = windowHeightM / 2f + GAP_M + panelHeightM / 2f + offsetM
    return Pose(window.t + window.q.times(Vector3(0f, if (above) up else -up, -FORWARD_M)), window.q)
  }
}

/** Shown on the thinking panel when its Stop was pressed. */
internal const val AI_STOPPED = "Stopped; nothing was typed"

/**
 * The AI mic's thinking panels. While the Mac's agent works out the keystrokes for a terminal
 * window (an AI pane in [Phase.Thinking] asked with thinking on), a panel just above or below that
 * window streams its thinking and each tool call, with Stop. Once it answers, the panel shows what
 * it typed and why for a few seconds, or why nothing was typed, then hides. One panel per window
 * (the newest run); panels are pooled, as each mount registers a new panel. Main thread only.
 */
internal class ThinkingPanels(private val xmux: XmuxSpatial, private val onStop: (VoiceNote) -> Unit) {
  private class Run(val note: VoiceNote, val window: ScreenWindow) {
    /** When the agent stopped thinking (answered, failed or was stopped); 0 while it thinks. */
    var endedAt = 0L
  }

  private class Slot(val entity: Entity) {
    var run by mutableStateOf<Run?>(null)
    var shown = false
      private set

    fun show(value: Boolean) {
      if (shown == value) return
      entity.setComponent(Visible(value))
      shown = value
    }
  }

  private val runs = mutableListOf<Run>()
  private val slots = mutableListOf<Slot>()
  private val panelHeightM: Float get() = HEIGHT_PX * xmux.config.windowWidthM / xmux.spec.widthPx

  /**
   * Picks up new agent runs among [notes] (their window from [windowOf], the window focused when the
   * fist opened), ends finished ones after a while, and places each shown panel [above] or below
   * its window. Call every frame.
   */
  fun tick(notes: List<VoiceNote>, windowOf: (VoiceNote) -> ScreenWindow?, above: Boolean, now: Long,
    occupiedAbove: (ScreenWindow) -> Float = { 0f }) {
    for (note in notes) {
      if (note.mode != VoiceNote.Mode.Ai || !note.agent || note.phase != Phase.Thinking || runs.any { it.note == note }) continue
      val window = windowOf(note) ?: continue
      runs.removeAll { it.window == window && it.endedAt != 0L } // a new run replaces a finished one there
      runs.add(Run(note, window))
      Log.i(TAG, "thinking panel for pane ${note.id} on screen ${window.screen?.value} (${if (above) "above" else "below"})")
    }
    for (run in runs) if (run.endedAt == 0L && run.note.phase != Phase.Thinking) run.endedAt = now
    runs.removeAll { it.window !in xmux.windows || (it.endedAt != 0L && now - it.endedAt > lingerMs(it.note)) }
    val shown = runs.groupBy { it.window }.values.map { it.last() }
    for (slot in slots) {
      if (slot.run != null && slot.run !in shown) {
        slot.show(false)
        slot.run = null
      }
    }
    for (run in shown) if (slots.none { it.run == run }) (slots.firstOrNull { it.run == null } ?: newSlot()).run = run
    for (slot in slots) {
      val run = slot.run ?: continue
      val pose = run.window.pose()?.takeIf { run.window.visible }
      if (pose == null) {
        slot.show(false)
        continue
      }
      slot.entity.setComponent(Transform(ThinkingPlacement.pose(pose, run.window.heightM, panelHeightM, above,
        if (above) occupiedAbove(run.window) else 0f)))
      slot.show(true)
    }
  }

  fun close() {
    slots.forEach { it.entity.destroy() }
    slots.clear()
    runs.clear()
  }

  private fun newSlot(): Slot {
    lateinit var slot: Slot
    val entity = xmux.mountPanel(WIDTH_PX, HEIGHT_PX, Placement.InFrontOfHead) {
      slot.run?.let { ThinkingPanel(it.note, onStop) }
    }
    // Keep its IsdkGrabbable: ISDK's panel systems read it from every panel, and removing it crashes
    // the frame. A grab does nothing lasting, as the panel is put back by its window every frame.
    entity.setComponent(Visible(false))
    slot = Slot(entity)
    slots.add(slot)
    return slot
  }

  /** How long a finished panel stays: long enough to read an answer, longer for a problem. */
  private fun lingerMs(note: VoiceNote): Long = when (note.notice) {
    null -> ANSWER_MS
    AI_STOPPED -> STOPPED_MS
    else -> PROBLEM_MS
  }

  companion object {
    private const val TAG = "vrcode"
    const val WIDTH_PX = 560
    const val HEIGHT_PX = 200
    const val ANSWER_MS = 6_000L
    const val PROBLEM_MS = 10_000L
    const val STOPPED_MS = 2_500L
  }
}

/**
 * One thinking panel: a header with the run's state and where the agent runs, then its thinking
 * (dim italic) and tool calls (monospace) as they stream, newest at the bottom; Stop while it
 * thinks. Done, it shows the agent's summary of what it did (and any keystrokes typed for it);
 * failed or stopped, why.
 */
@Composable
fun ThinkingPanel(note: VoiceNote, onStop: (VoiceNote) -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  val thinking = note.phase == Phase.Thinking
  val problem = note.notice
  val answered = !thinking && problem == null && note.aiKeys != null
  val accent = when {
    thinking -> Violet
    answered -> Done
    else -> Problem
  }
  Column(
    Modifier.fillMaxSize().clip(shape).background(Face).border(if (thinking) 2.dp else 1.dp, if (thinking) Violet else Rim, shape)
      .padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      val pulse = if (thinking) {
        val transition = rememberInfiniteTransition(label = "thinking")
        transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
      } else 1f
      Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(accent))
      Spacer(Modifier.size(8.dp))
      BasicText(
        when {
          thinking -> "Thinking…"
          answered && note.aiKeys.isNullOrEmpty() -> "Done" // the agent worked in the terminal itself
          answered -> "Typed"
          problem == AI_STOPPED -> "Stopped"
          else -> "Nothing typed"
        },
        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
      )
      Spacer(Modifier.size(8.dp))
      BasicText(
        note.aiStatus,
        style = TextStyle(color = Muted, fontSize = 12.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (thinking) {
        BasicText(
          "Stop",
          style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
          modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(StopFace)
            .semantics { role = Role.Button; contentDescription = "Stop the AI" }
            .clickable { onStop(note) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        )
      }
    }
    Spacer(Modifier.height(6.dp))
    if (thinking) {
      Trace(note, Modifier.weight(1f))
    } else {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (problem != null) {
          BasicText(problem, style = TextStyle(color = Problem, fontSize = 14.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
          if (note.aiSummary.isNotBlank()) {
            BasicText(note.aiSummary, style = TextStyle(color = Color.White, fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
          note.aiKeys?.takeIf { it.isNotEmpty() }?.let { keys ->
            BasicText(
              keystrokeText(keys),
              style = TextStyle(color = StepText, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/** The run so far, scrolled to its newest line. */
@Composable
private fun Trace(note: VoiceNote, modifier: Modifier) {
  val scroll = rememberScrollState()
  LaunchedEffect(note.trace.size, (note.trace.lastOrNull() as? AiTrace.Thought)?.text?.length) { scroll.scrollTo(scroll.maxValue) }
  Column(modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    if (note.trace.isEmpty()) {
      BasicText("Starting…", style = TextStyle(color = Muted, fontSize = 13.sp, fontStyle = FontStyle.Italic))
    }
    for (entry in note.trace) {
      when (entry) {
        is AiTrace.Thought ->
          BasicText(entry.text.trim(), style = TextStyle(color = Thought, fontSize = 13.sp, fontStyle = FontStyle.Italic))
        is AiTrace.Step ->
          BasicText(
            "› ${entry.text}",
            style = TextStyle(color = StepText, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
      }
    }
  }
}
