package dev.improve.vrcode.keyboard

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** What a key does; the feature turns it into terminal keys or voice-pane edits. */
sealed interface KeyAction {
  data class Text(val text: String) : KeyAction

  /** A named terminal key in cmux's vocabulary ("escape", "tab", "up", ...). */
  data class Named(val name: String) : KeyAction

  data object Backspace : KeyAction

  data object Enter : KeyAction
}

/** Keyboard page and one-shot modifiers. Main thread only. */
class KeyboardState {
  enum class Page { Letters, Numbers, Symbols }

  var page by mutableStateOf(Page.Letters)
  var shift by mutableStateOf(false)
  var ctrl by mutableStateOf(false)

  /** Where typing goes, shown in the header ("focused pane", "voice pane 3"). */
  var target by mutableStateOf("focused pane")
}

private val Board = Color(0xF0202227)
private val KeyFace = Color(0xFF4A4D55)
private val ModKey = Color(0xFF33363C)
private val Pressed = Color(0xFF8A8F98)
private val Active = Color(0xFF4C8DFF)
private val Label = Color.White
private val Hint = Color(0xFF9AA0A8)

private const val REPEAT_GUARD_MS = 120L

/**
 * An iPhone-style floating keyboard, typed by touching keys with a fingertip (Interaction SDK
 * direct touch turns a touch into a press) or by pinching with a hand's ray. Keys fire on
 * touch-down so typing feels immediate; a short guard drops the jitter of a finger resting on the
 * surface. A terminal row (esc, tab, ctrl, arrows) sits above the letters, like iOS SSH clients
 * have.
 */
@Composable
fun TouchKeyboard(state: KeyboardState, onKey: (KeyAction) -> Unit, onClose: () -> Unit) {
  val lastPress = remember { longArrayOf(0L) }
  val lastKey = remember { arrayOf<String?>(null) }
  fun press(id: String, action: () -> Unit) {
    val now = SystemClock.uptimeMillis()
    if (id == lastKey[0] && now - lastPress[0] < REPEAT_GUARD_MS) return
    lastKey[0] = id
    lastPress[0] = now
    action()
  }
  fun type(text: String) =
    press(text) {
      val out = if (state.shift) text.uppercase() else text
      onKey(KeyAction.Text(out))
      if (state.shift) state.shift = false
    }

  BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Board).padding(4.dp)) {
    val rowHeight = maxHeight / 6f
    val fontPx = with(LocalDensity.current) { (rowHeight * 0.42f).toSp() }
    val small = with(LocalDensity.current) { (rowHeight * 0.3f).toSp() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      // Header: where the text goes.
      Row(Modifier.fillMaxWidth().weight(0.7f), verticalAlignment = Alignment.CenterVertically) {
        BasicText("→ ${state.target}", style = TextStyle(color = Hint, fontSize = small), modifier = Modifier.weight(1f))
        Box(Modifier.fillMaxHeight().weight(0.12f)) { Row(Modifier.fillMaxSize()) { Key("×", small, ModKey) { press("close") { onClose() } } } }
      }
      KeyRow {
        Key("esc", small, ModKey) { press("esc") { onKey(KeyAction.Named("escape")) } }
        Key("tab", small, ModKey) { press("tab") { onKey(KeyAction.Named("tab")) } }
        Key("ctrl", small, if (state.ctrl) Active else ModKey) { press("ctrl") { state.ctrl = !state.ctrl } }
        Key("←", small, ModKey) { press("left") { onKey(KeyAction.Named("left")) } }
        Key("↓", small, ModKey) { press("down") { onKey(KeyAction.Named("down")) } }
        Key("↑", small, ModKey) { press("up") { onKey(KeyAction.Named("up")) } }
        Key("→", small, ModKey) { press("right") { onKey(KeyAction.Named("right")) } }
      }
      val rows =
        when (state.page) {
          KeyboardState.Page.Letters -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
          KeyboardState.Page.Numbers -> listOf("1234567890", "-/:;()$&@\"", ".,?!'")
          KeyboardState.Page.Symbols -> listOf("[]{}#%^*+=", "_\\|~<>`'\"·", ".,?!'")
        }
      KeyRow { for (c in rows[0]) Key(display(c, state), fontPx) { type(c.toString()) } }
      KeyRow(inset = if (rows[1].length < 10) 0.5f else 0f) { for (c in rows[1]) Key(display(c, state), fontPx) { type(c.toString()) } }
      KeyRow {
        when (state.page) {
          KeyboardState.Page.Letters ->
            Key("⇧", fontPx, if (state.shift) Active else ModKey, weight = 1.5f) { press("shift") { state.shift = !state.shift } }
          KeyboardState.Page.Numbers ->
            Key("#+=", small, ModKey, weight = 1.5f) { press("page") { state.page = KeyboardState.Page.Symbols } }
          KeyboardState.Page.Symbols ->
            Key("123", small, ModKey, weight = 1.5f) { press("page") { state.page = KeyboardState.Page.Numbers } }
        }
        Spacer(Modifier.weight(0.2f))
        for (c in rows[2]) Key(display(c, state), fontPx, weight = if (state.page == KeyboardState.Page.Letters) 1f else 1.4f) { type(c.toString()) }
        Spacer(Modifier.weight(0.2f))
        Key("⌫", fontPx, ModKey, weight = 1.5f) { press("backspace") { onKey(KeyAction.Backspace) } }
      }
      KeyRow {
        val toLetters = state.page != KeyboardState.Page.Letters
        Key(if (toLetters) "ABC" else "123", small, ModKey, weight = 2f) {
          press("page") { state.page = if (toLetters) KeyboardState.Page.Letters else KeyboardState.Page.Numbers }
        }
        Key("space", small, weight = 5f) { press("space") { onKey(KeyAction.Text(" ")) } }
        Key("return", small, Active, weight = 2.5f) { press("return") { onKey(KeyAction.Enter) } }
      }
    }
  }
}

private fun display(c: Char, state: KeyboardState): String = if (state.shift) c.uppercaseChar().toString() else c.toString()

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.KeyRow(inset: Float = 0f, content: @Composable RowScope.() -> Unit) {
  Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    if (inset > 0f) Spacer(Modifier.weight(inset))
    content()
    if (inset > 0f) Spacer(Modifier.weight(inset))
  }
}

@Composable
private fun RowScope.Key(
  label: String,
  fontSize: androidx.compose.ui.unit.TextUnit,
  face: Color = KeyFace,
  weight: Float = 1f,
  onPress: () -> Unit,
) {
  var pressed by remember { mutableStateOf(false) }
  val currentOnPress by rememberUpdatedState(onPress)
  Box(
    Modifier.weight(weight)
      .fillMaxHeight()
      .clip(RoundedCornerShape(5.dp))
      .background(if (pressed) Pressed else face)
      .pointerInput(Unit) {
        detectTapGestures(
          onPress = {
            pressed = true
            currentOnPress()
            tryAwaitRelease()
            pressed = false
          },
        )
      },
    contentAlignment = Alignment.Center,
  ) {
    BasicText(label, style = TextStyle(color = Label, fontSize = fontSize, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
  }
}
