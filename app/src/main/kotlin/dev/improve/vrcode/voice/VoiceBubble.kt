package dev.improve.vrcode.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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

/**
 * The current voice pane: a dictation pane (right fist) shows what was said; an AI pane (left fist)
 * shows the AI's text for the focused pane above what was said. A paste types the dictation as is,
 * or the AI's text.
 */
@Composable
fun VoiceBubble(note: VoiceNote, voice: VoiceController, onPasteFocused: (VoiceNote) -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  val listening = voice.listening == note
  val editing = voice.editing == note
  val selected = voice.selected == note
  val edge = when {
    listening -> Live
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
      Box(Modifier.size(10.dp).clip(CircleShape).background(if (listening) Live.copy(alpha = 0.5f + voice.level / 2f) else edge))
      Spacer(Modifier.size(8.dp))
      val ai = note.mode == VoiceNote.Mode.Ai
      BasicText(
        if (ai) "AI" else "Dictation",
        style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (ai) AiBadge else Color(0x33FFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp),
      )
      Spacer(Modifier.size(8.dp))
      BasicText(statusLine(note, listening, editing, selected), style = TextStyle(color = Dim, fontSize = 12.sp), modifier = Modifier.weight(1f))
      Chip("Delete") { voice.remove(note) }
    }
    Spacer(Modifier.height(6.dp))
    val scroll = rememberScrollState()
    val committed = joinText(note.committed, note.liveFinal)
    LaunchedEffect(committed, note.liveVolatile) { scroll.scrollTo(scroll.maxValue) }
    val aiText = note.aiText
    if (note.mode == VoiceNote.Mode.Ai && aiText != null) {
      // The text a paste types, above what was said.
      BasicText(aiText, style = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace))
      Spacer(Modifier.height(4.dp))
    }
    note.notice?.takeIf { committed.isNotEmpty() || note.liveVolatile.isNotBlank() }?.let {
      BasicText(it, style = TextStyle(color = Editing, fontSize = 12.sp))
      Spacer(Modifier.height(2.dp))
    }
    val heard = if (note.mode == VoiceNote.Mode.Ai && aiText != null) Dim else Color.White
    BasicText(
      buildAnnotatedString {
        withStyle(SpanStyle(color = heard)) { append(committed) }
        if (note.liveVolatile.isNotBlank()) {
          if (committed.isNotEmpty()) append(" ")
          withStyle(SpanStyle(color = Dim)) { append(note.liveVolatile.trim()) }
        }
        if (committed.isEmpty() && note.liveVolatile.isBlank()) {
          withStyle(SpanStyle(color = Dim)) { append(note.notice ?: if (listening) "Say something…" else "") }
        }
      },
      style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
    )
    if (!listening && note.phase != Phase.Thinking && note.output.isNotBlank()) {
      Spacer(Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(if (voice.enterAfterPaste) "Enter after: on" else "Enter after: off", selected = voice.enterAfterPaste) {
          voice.enterAfterPaste = !voice.enterAfterPaste
        }
        Spacer(Modifier.weight(1f))
        Chip("Paste into focused pane", selected = true) { onPasteFocused(note) }
      }
    }
  }
}

private fun statusLine(note: VoiceNote, listening: Boolean, editing: Boolean, selected: Boolean): String =
  when {
    listening && note.mode == VoiceNote.Mode.Ai -> "Listening to you for the AI — open your hand when done"
    listening -> "Listening — open your hand to stop"
    note.phase == Phase.Finalizing -> "Finishing…"
    note.phase == Phase.Thinking -> "Thinking…"
    editing -> "Editing — make a fist to add to this pane"
    selected -> "Point your left hand at a pane and pinch to paste"
    else -> "Pinch to select"
  }

@Composable
private fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  BasicText(
    label,
    style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    modifier =
      Modifier.clip(shape)
        .background(if (selected) Accent else Color(0x33FFFFFF))
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 5.dp),
  )
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
