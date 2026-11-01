package dev.improve.vrcode.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.improve.vrcode.speech.joinText
import dev.improve.vrcode.voice.VoiceNote.Phase

private val Panel = Color(0xE6181A20)
private val Edge = Color(0x40FFFFFF)
private val Dim = Color(0xFF8A8F98)
private val Live = Color(0xFFFF5A5F)
private val Accent = Color(0xFF4C8DFF)
private val Editing = Color(0xFFF5B83D)
private val AiBadge = Color(0xFF8A5CF6)
private val ButtonFace = Color(0xFF3A3D45)
private val ButtonPressed = Color(0xFF5A5E68)
private val ButtonBase = Color(0xFF0E0F12)
private val AccentBase = Color(0xFF1F3F80)
private val EnterOn = Color(0xFF22A45D)
private val EnterOnBase = Color(0xFF0E4A28)
private val EnterOnRing = Color(0xFF7CF0A8)
private val EnterOff = Color(0xFF2A2D33)

/** How far a push button's face stands proud of its base. */
private val RAISE = 4.dp
private val TOGGLE_SIZE = 46.dp

/**
 * The current voice pane. While its fist is closed it shows the live transcript, where it will be
 * typed ([target], the focused window's title) and push buttons the other hand presses: on a
 * dictation, the text mic's Enter-after toggle in the bottom corner nearest that hand
 * ([freeHandLeft]: bottom left, as the right fist holds the text mic), and Cancel (type nothing)
 * and Clear (start over, still listening) at the far end. An AI pane has no toggle: the AI's
 * keystrokes, shown once it answers, include Enter when it chose to press it. A pane that could not
 * be typed shows why, with Delete and Paste.
 */
@Composable
fun VoiceBubble(
  note: VoiceNote,
  voice: VoiceController,
  target: String?,
  freeHandLeft: Boolean,
  onPasteFocused: (VoiceNote) -> Unit,
  onCancel: () -> Unit,
  onClear: () -> Unit,
  onToggleEnter: () -> Unit,
) {
  val shape = RoundedCornerShape(12.dp)
  val listening = voice.listening == note
  val editing = voice.editing == note
  val selected = voice.selected == note
  val ai = note.mode == VoiceNote.Mode.Ai
  val edge = when {
    listening -> if (ai) AiBadge else Live
    editing -> Editing
    selected -> Accent
    else -> Edge
  }
  Column(
    Modifier.fillMaxSize()
      .clip(shape)
      .background(Panel)
      .border(if (edge == Edge) 1.dp else 2.dp, edge, shape)
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { voice.select(note) }
      .padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).clip(CircleShape).background(if (listening) edge.copy(alpha = 0.5f + voice.level / 2f) else edge))
      Spacer(Modifier.size(8.dp))
      BasicText(
        if (ai) "AI" else "Dictation",
        style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (ai) AiBadge else Color(0x33FFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp),
      )
      Spacer(Modifier.size(8.dp))
      BasicText(
        statusLine(note, listening, editing, selected, target),
        style = TextStyle(color = Dim, fontSize = 12.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
    Spacer(Modifier.height(6.dp))
    val scroll = rememberScrollState()
    val committed = joinText(note.committed, note.liveFinal)
    LaunchedEffect(committed, note.liveVolatile) { scroll.scrollTo(scroll.maxValue) }
    val aiKeys = note.aiKeys
    if (ai && aiKeys != null) {
      // The keystrokes a paste sends, above what was said.
      BasicText(keystrokeText(aiKeys), style = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace))
      Spacer(Modifier.height(4.dp))
    }
    note.notice?.takeIf { committed.isNotEmpty() || note.liveVolatile.isNotBlank() }?.let {
      BasicText(it, style = TextStyle(color = Editing, fontSize = 12.sp))
      Spacer(Modifier.height(2.dp))
    }
    val heard = if (ai && aiKeys != null) Dim else Color.White
    BasicText(
      buildAnnotatedString {
        withStyle(SpanStyle(color = heard)) { append(committed) }
        if (note.liveVolatile.isNotBlank()) {
          if (committed.isNotEmpty()) append(" ")
          withStyle(SpanStyle(color = Dim)) { append(note.liveVolatile.trim()) }
        }
        if (committed.isEmpty() && note.liveVolatile.isBlank()) {
          // A live pane only shows once a word is transcribed, so an empty one is only ever a notice.
          withStyle(SpanStyle(color = Dim)) { append(note.notice ?: "") }
        }
      },
      style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
    )
    Spacer(Modifier.height(6.dp))
    // Only the text mic has an Enter-after toggle: the AI chooses Enter itself.
    val enterToggle: @Composable () -> Unit = { EnterToggle(on = voice.enterAfterDictation, cornerLeft = freeHandLeft, onToggle = onToggleEnter) }
    val toggle = enterToggle.takeUnless { ai }
    if (listening) {
      // Pressed with the free hand while the other one holds the fist.
      ButtonRow(freeHandLeft, toggle) {
        TouchButton("Cancel", "voice-cancel", onPress = onCancel)
        TouchButton("Clear", "voice-clear", onPress = onClear)
      }
    } else if (note.phase != Phase.Thinking && note.phase != Phase.Finalizing) {
      ButtonRow(freeHandLeft, toggle) {
        TouchButton("Delete", "voice-delete") { voice.remove(note) }
        if (note.output.isNotEmpty()) TouchButton("Paste to focused", "voice-paste", selected = true) { onPasteFocused(note) }
      }
    }
  }
}

/**
 * The pane's bottom row: [toggle] (if any) in the corner nearest the free hand, the other buttons
 * at the far end.
 */
@Composable
private fun ButtonRow(toggleLeft: Boolean, toggle: (@Composable () -> Unit)?, others: @Composable RowScope.() -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    if (toggleLeft) toggle?.invoke() else others()
    Spacer(Modifier.weight(1f))
    if (toggleLeft) others() else toggle?.invoke()
  }
}

/** The AI's keystrokes as the pane shows them: text as typed, each key press as a chip (⏎, ⌃C, Esc). */
internal fun keystrokeText(steps: List<Keystroke>) =
  buildAnnotatedString {
    for (step in steps) {
      when (step) {
        is Keystroke.Text -> append(step.text)
        is Keystroke.Press ->
          withStyle(SpanStyle(color = Color.White, background = AiBadge, fontWeight = FontWeight.Bold)) { append(" ${step.label} ") }
      }
    }
  }

private fun statusLine(note: VoiceNote, listening: Boolean, editing: Boolean, selected: Boolean, target: String?): String {
  val into = target?.ifBlank { "the focused pane" } ?: "no terminal open"
  return when {
    listening && note.mode == VoiceNote.Mode.Ai -> "For the AI → $into · open your hand when done"
    listening -> "→ $into · open your hand to type it"
    note.phase == Phase.Finalizing -> "Finishing…"
    note.phase == Phase.Thinking -> "Thinking…"
    editing -> "Editing — make a fist to add to this pane"
    selected -> "Point your left hand at a pane and pinch to paste"
    else -> "Pinch to select"
  }
}

/**
 * Acts on press, like the touch keyboard's keys, so a poke from the free hand registers without a
 * clean release; [pressed] is true while the finger stays on it.
 */
@Composable
private fun Modifier.pushable(pressed: MutableState<Boolean>, onPress: () -> Unit): Modifier {
  val currentOnPress by rememberUpdatedState(onPress)
  return pointerInput(Unit) {
    detectTapGestures(
      onPress = {
        pressed.value = true
        currentOnPress()
        tryAwaitRelease()
        pressed.value = false
      },
    )
  }
}

/**
 * A push button's look: the face stands [lift] proud of a darker base, so it reads as something to
 * push, and sinks onto the base while pressed.
 */
@Composable
private fun PushCap(shape: Shape, face: Color, base: Color, lift: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(modifier.padding(top = RAISE).background(base, shape)) {
    Box(Modifier.offset(y = -lift).clip(shape).background(face), contentAlignment = Alignment.Center, content = content)
  }
}

/** A push button sized for a fingertip. */
@Composable
private fun TouchButton(label: String, description: String, selected: Boolean = false, onPress: () -> Unit) {
  val pressed = remember { mutableStateOf(false) }
  val face = if (pressed.value) ButtonPressed else if (selected) Accent else ButtonFace
  PushCap(
    RoundedCornerShape(8.dp),
    face,
    base = if (selected) AccentBase else ButtonBase,
    lift = if (pressed.value) 0.dp else RAISE,
    modifier = Modifier.semantics { contentDescription = description }.pushable(pressed, onPress),
  ) {
    BasicText(
      label,
      style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
      modifier = Modifier.height(38.dp).padding(horizontal = 14.dp).wrapContentHeight(),
    )
  }
}

/**
 * The Enter-after toggle: a round, latching push button with a return-key icon, with its state
 * spelled out beside it, toward the pane ([cornerLeft] puts the button in the bottom-left corner).
 * On, it glows green, rests pushed in and reads ON; off, it is dark, stands proud and reads OFF.
 * The label is part of the target, so a poke that lands just beside the button still counts.
 */
@Composable
private fun EnterToggle(on: Boolean, cornerLeft: Boolean, onToggle: () -> Unit) {
  val pressed = remember { mutableStateOf(false) }
  val lift = when {
    pressed.value -> 0.dp
    on -> RAISE / 2
    else -> RAISE
  }
  Row(
    Modifier.semantics {
      contentDescription = "voice-enter-after"
      stateDescription = if (on) "on" else "off"
      role = Role.Switch
    }.pushable(pressed, onToggle),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val button = @Composable {
      PushCap(CircleShape, if (on) EnterOn else EnterOff, base = if (on) EnterOnBase else ButtonBase, lift = lift) {
        Box(
          Modifier.size(TOGGLE_SIZE).border(2.dp, if (on) EnterOnRing else Edge, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          ReturnIcon(if (on) Color.White else Dim)
        }
      }
    }
    val label = @Composable {
      Column(horizontalAlignment = if (cornerLeft) Alignment.Start else Alignment.End) {
        BasicText("Auto-Enter", style = TextStyle(color = Dim, fontSize = 11.sp))
        BasicText(
          if (on) "ON" else "OFF",
          style = TextStyle(color = if (on) EnterOnRing else Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
      }
    }
    if (cornerLeft) {
      button()
      label()
    } else {
      label()
      button()
    }
  }
}

/** A return key's arrow: down from the top right, then left, with an arrowhead. */
@Composable
private fun ReturnIcon(color: Color) {
  Canvas(Modifier.size(22.dp)) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(w * 0.8f, h * 0.15f); lineTo(w * 0.8f, h * 0.62f); lineTo(w * 0.2f, h * 0.62f) }, color, style = stroke)
    drawPath(Path().apply { moveTo(w * 0.4f, h * 0.42f); lineTo(w * 0.2f, h * 0.62f); lineTo(w * 0.4f, h * 0.82f) }, color, style = stroke)
  }
}

/** How a terminal window's header marks it as where voice goes. */
enum class VoiceTarget { Focused, Dictating, TalkingToAi, Thinking, Typing }

/**
 * A badge for a terminal window's header: a dot on the focused window (where voice and keys go),
 * which grows into what voice is doing there — listening, or finishing/asking the AI before it
 * types.
 */
@Composable
fun VoiceTargetBadge(target: VoiceTarget?, level: () -> Float) {
  target ?: return
  val (color, label) =
    when (target) {
      VoiceTarget.Focused -> Accent to null
      VoiceTarget.Dictating -> Live to "Dictating"
      VoiceTarget.TalkingToAi -> AiBadge to "Talking to AI"
      VoiceTarget.Thinking -> AiBadge to "Thinking…"
      VoiceTarget.Typing -> AiBadge to "Typing…"
    }
  val listening = target == VoiceTarget.Dictating || target == VoiceTarget.TalkingToAi
  Row(
    Modifier.padding(horizontal = 6.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(if (label == null) Color.Transparent else color.copy(alpha = 0.22f))
      .padding(horizontal = if (label == null) 4.dp else 10.dp, vertical = 4.dp)
      .semantics { contentDescription = label ?: "Voice target" },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Only a listening badge reads the level, so other windows' headers do not redraw with it.
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (listening) color.copy(alpha = 0.5f + level() / 2f) else color))
    if (label != null) {
      Spacer(Modifier.size(6.dp))
      BasicText(label, style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
  }
}

/**
 * Placeholder for the virtual microphone held in the left fist (the 3D model in
 * assets/microphone/ replaces it): a round badge whose ring follows the input level.
 */
@Composable
fun MicMarker(voice: VoiceController) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val ring = 0.55f + 0.45f * voice.level
    Box(
      Modifier.size((56 * ring + 8).dp).clip(CircleShape).background(Live.copy(alpha = 0.35f)),
      contentAlignment = Alignment.Center,
    ) {
      Box(Modifier.size(36.dp).clip(CircleShape).background(Live), contentAlignment = Alignment.Center) {
        BasicText("MIC", style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
      }
    }
  }
}
